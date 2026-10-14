use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use rand::RngExt;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

#[derive(Debug, Clone, Copy)]
pub struct FragmentConfig {
    pub enabled: bool,
    pub size_min: usize,
    pub size_max: usize,
    pub delay_min_ms: u64,
    pub delay_max_ms: u64,
    pub sni_split: bool,
}

impl FragmentConfig {
    pub fn disabled() -> Self {
        Self {
            enabled: false,
            size_min: 1,
            size_max: 1,
            delay_min_ms: 0,
            delay_max_ms: 0,
            sni_split: false,
        }
    }

    pub fn from_env() -> Self {
        let enabled = std::env::var("AETHER_MASQUE_H2_FRAGMENT")
            .map(|v| is_truthy(&v))
            .unwrap_or(false);

        let (size_min, size_max) = parse_range(
            &std::env::var("AETHER_MASQUE_H2_FRAGMENT_SIZE").unwrap_or_default(),
            (16, 32),
        );
        let (delay_min_ms, delay_max_ms) = parse_range(
            &std::env::var("AETHER_MASQUE_H2_FRAGMENT_DELAY").unwrap_or_default(),
            (2, 10),
        );

        let size_min = size_min.max(1) as usize;
        let size_max = (size_max.max(size_min as u64)) as usize;

        let sni_split = std::env::var("AETHER_MASQUE_H2_FRAGMENT_SNI")
            .map(|v| is_truthy(&v))
            .unwrap_or(true);

        Self {
            enabled,
            size_min,
            size_max,
            delay_min_ms,
            delay_max_ms: delay_max_ms.max(delay_min_ms),
            sni_split,
        }
    }

    fn pick_chunk_len(&self, remaining: usize) -> usize {
        let hi = self.size_max.max(1).min(remaining);
        let lo = self.size_min.max(1).min(hi);
        if lo >= hi {
            hi
        } else {
            rand::rng().random_range(lo..=hi)
        }
    }

    fn pick_delay(&self) -> Duration {
        if self.delay_max_ms == 0 {
            return Duration::ZERO;
        }
        let ms = if self.delay_max_ms <= self.delay_min_ms {
            self.delay_min_ms
        } else {
            rand::rng().random_range(self.delay_min_ms..=self.delay_max_ms)
        };
        Duration::from_millis(ms)
    }
}

pub(crate) fn is_truthy(v: &str) -> bool {
    matches!(
        v.trim().to_lowercase().as_str(),
        "1" | "true" | "yes" | "on"
    )
}

fn parse_range(spec: &str, default: (u64, u64)) -> (u64, u64) {
    let spec = spec.trim();
    if spec.is_empty() {
        return default;
    }
    match spec.split_once('-') {
        Some((a, b)) => {
            let lo = a.trim().parse().unwrap_or(default.0);
            let hi = b.trim().parse().unwrap_or(default.1);
            if hi < lo {
                (hi, lo)
            } else {
                (lo, hi)
            }
        }
        None => {
            let v = spec.parse().unwrap_or(default.0);
            (v, v)
        }
    }
}

pub fn sni_host_range(buf: &[u8]) -> Option<(usize, usize)> {
    let take = |at: usize, n: usize| -> Option<usize> {
        let end = at.checked_add(n)?;
        let slice = buf.get(at..end)?;
        Some(slice.iter().fold(0usize, |acc, b| (acc << 8) | *b as usize))
    };

    if *buf.first()? != 0x16 || *buf.get(5)? != 0x01 {
        return None;
    }

    let mut at = 43usize;
    at += 1 + take(at, 1)?;
    at += 2 + take(at, 2)?;
    at += 1 + take(at, 1)?;

    let extensions_end = at + 2 + take(at, 2)?;
    at += 2;

    while at + 4 <= extensions_end {
        let kind = take(at, 2)?;
        let len = take(at + 2, 2)?;
        let body = at + 4;
        if kind == 0x0000 {
            let entry = body + 2;
            if take(entry, 1)? != 0 {
                return None;
            }
            let host_len = take(entry + 1, 2)?;
            let host = entry + 3;
            if host_len == 0 || host + host_len > buf.len() {
                return None;
            }
            return Some((host, host + host_len));
        }
        at = body + len;
    }

    None
}

pub struct FragmentingStream<S> {
    inner: S,
    cfg: FragmentConfig,
    fragmenting: bool,
    first_write: bool,
    pending_delay: Option<Pin<Box<tokio::time::Sleep>>>,
    hello_shape: Option<crate::hello_shape::HelloShape>,
    pending_hello: Option<crate::hello_shape::PendingHello>,
    hello_input: Option<Vec<u8>>,
}

impl<S> FragmentingStream<S> {
    pub fn new(inner: S, cfg: FragmentConfig) -> Self {
        Self {
            inner,
            fragmenting: cfg.enabled,
            cfg,
            first_write: true,
            pending_delay: None,
            hello_shape: if cfg.enabled {
                crate::hello_shape::HelloShape::from_env()
            } else {
                None
            },
            pending_hello: None,
            hello_input: None,
        }
    }

    fn poll_hello(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>>
    where
        S: AsyncWrite + Unpin,
    {
        if let Some(pending) = &mut self.pending_hello {
            std::task::ready!(pending.poll_drain(&mut self.inner, cx))?;
            self.pending_hello = None;
        }
        Poll::Ready(Ok(()))
    }
}

impl<S> AsyncRead for FragmentingStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        std::task::ready!(this.poll_hello(cx))?;
        this.fragmenting = false;
        Pin::new(&mut this.inner).poll_read(cx, buf)
    }
}

impl<S> AsyncWrite for FragmentingStream<S>
where
    S: AsyncWrite + Unpin,
{
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();

        if let Some(original) = &this.hello_input {
            if buf != original.as_slice() {
                return Poll::Ready(Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "a pending ClientHello must be retried with the same bytes",
                )));
            }
            let accepted = original.len();
            std::task::ready!(this.poll_hello(cx))?;
            this.hello_input = None;
            return Poll::Ready(Ok(accepted));
        }
        if this.first_write && !buf.is_empty() {
            if let Some(shape) = this.hello_shape {
                this.first_write = false;
                this.fragmenting = false;
                if let Some(bytes) = shape.encode(buf) {
                    this.pending_hello = Some(shape.pending(bytes));
                    this.hello_input = Some(buf.to_vec());
                    // BoringSSL BIO flush cannot suspend after an accepted
                    // write. Keep the BIO in WANT_WRITE until the shaped bytes
                    // have drained, then acknowledge the original input once.
                    std::task::ready!(this.poll_hello(cx))?;
                    this.hello_input = None;
                    return Poll::Ready(Ok(buf.len()));
                }
            }
        }

        if buf.is_empty() || !this.fragmenting {
            return Pin::new(&mut this.inner).poll_write(cx, buf);
        }

        if let Some(sleep) = this.pending_delay.as_mut() {
            match sleep.as_mut().poll(cx) {
                Poll::Ready(()) => this.pending_delay = None,
                Poll::Pending => return Poll::Pending,
            }
        }

        let targeted = if this.first_write && this.cfg.sni_split {
            sni_host_range(buf).map(|(start, end)| start + (end - start) / 2)
        } else {
            None
        };
        let chunk_len = match targeted {
            Some(split) if split > 0 && split < buf.len() => split,
            _ => this.cfg.pick_chunk_len(buf.len()),
        };
        match Pin::new(&mut this.inner).poll_write(cx, &buf[..chunk_len]) {
            Poll::Ready(Ok(n)) => {
                if n > 0 {
                    this.first_write = false;
                    let delay = this.cfg.pick_delay();
                    if !delay.is_zero() {
                        this.pending_delay = Some(Box::pin(tokio::time::sleep(delay)));
                    }
                }
                Poll::Ready(Ok(n))
            }
            other => other,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        std::task::ready!(this.poll_hello(cx))?;
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        std::task::ready!(this.poll_hello(cx))?;
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn client_hello(host: &str) -> Vec<u8> {
        let mut sni = vec![0x00];
        sni.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni.extend_from_slice(host.as_bytes());

        let mut list = (sni.len() as u16).to_be_bytes().to_vec();
        list.extend_from_slice(&sni);

        let mut ext = vec![0x00, 0x00];
        ext.extend_from_slice(&(list.len() as u16).to_be_bytes());
        ext.extend_from_slice(&list);

        let mut body = vec![0x03, 0x03];
        body.extend_from_slice(&[0u8; 32]);
        body.push(0x00);
        body.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        body.extend_from_slice(&[0x01, 0x00]);
        body.extend_from_slice(&(ext.len() as u16).to_be_bytes());
        body.extend_from_slice(&ext);

        let mut handshake = vec![0x01];
        handshake.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
        handshake.extend_from_slice(&body);

        let mut record = vec![0x16, 0x03, 0x01];
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }

    #[test]
    fn the_server_name_is_located_inside_a_client_hello() {
        let host = "api.cloudflareclient.com";
        let hello = client_hello(host);
        let (start, end) = sni_host_range(&hello).expect("the name is in there");
        assert_eq!(&hello[start..end], host.as_bytes());
    }

    #[test]
    fn a_split_lands_in_the_middle_of_the_server_name() {
        let host = "api.cloudflareclient.com";
        let hello = client_hello(host);
        let (start, end) = sni_host_range(&hello).expect("the name is in there");
        let split = start + (end - start) / 2;
        assert!(split > start && split < end);
    }

    #[test]
    fn anything_that_is_not_a_client_hello_is_left_alone() {
        assert!(sni_host_range(b"").is_none());
        assert!(sni_host_range(&[0x17, 0x03, 0x03, 0x00, 0x05, 0x01]).is_none());
        let truncated = &client_hello("example.com")[..20];
        assert!(sni_host_range(truncated).is_none());
    }

    #[test]
    fn targeted_splitting_is_on_by_default_once_fragmenting_is_asked_for() {
        assert!(!FragmentConfig::disabled().sni_split);
    }

    #[tokio::test]
    async fn shaped_hello_survives_short_writes_flush_and_shutdown() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        for shape in [
            crate::hello_shape::HelloShape::Records,
            crate::hello_shape::HelloShape::Compact,
        ] {
            let hello = client_hello("consumer-masque.cloudflareclient.com");
            let mut expected = shape.encode(&hello).unwrap();
            let suffix = [23, 3, 3, 0, 2, 42, 43];
            expected.extend_from_slice(&suffix);
            // Seven bytes forces partial underlying writes at every large boundary.
            let (client, mut server) = tokio::io::duplex(7);
            let receiver = tokio::spawn(async move {
                let mut bytes = Vec::new();
                server.read_to_end(&mut bytes).await.unwrap();
                bytes
            });
            let mut writer = FragmentingStream::new(client, FragmentConfig::disabled());
            writer.hello_shape = Some(shape);
            writer.write_all(&hello).await.unwrap();
            writer.flush().await.unwrap();
            writer.write_all(&suffix).await.unwrap();
            writer.shutdown().await.unwrap();
            assert_eq!(receiver.await.unwrap(), expected);
        }
    }

    #[tokio::test]
    async fn reading_drains_an_accepted_hello_before_waiting_for_the_server() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let shape = crate::hello_shape::HelloShape::Compact;
        let hello = client_hello("example.com");
        let expected = shape.encode(&hello).unwrap();
        let (client, mut server) = tokio::io::duplex(7);
        let responder = tokio::spawn(async move {
            let mut bytes = vec![0; expected.len()];
            server.read_exact(&mut bytes).await.unwrap();
            assert_eq!(bytes, expected);
            server.write_all(b"reply").await.unwrap();
        });
        let mut writer = FragmentingStream::new(client, FragmentConfig::disabled());
        writer.hello_shape = Some(shape);
        writer.write_all(&hello).await.unwrap();
        let mut reply = [0; 5];
        tokio::time::timeout(Duration::from_secs(2), writer.read_exact(&mut reply))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(&reply, b"reply");
        responder.await.unwrap();
    }

    #[tokio::test]
    async fn both_record_shapes_complete_a_real_tls_handshake_and_exchange_data() {
        use boring::{
            pkey::PKey,
            ssl::{SslAcceptor, SslConnector, SslMethod, SslVerifyMode},
            x509::X509,
        };
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let pair = crate::account::generate_masque_keypair().unwrap();
        let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).unwrap();
        acceptor
            .set_certificate(&X509::from_pem(&pair.cert_pem).unwrap())
            .unwrap();
        acceptor
            .set_private_key(&PKey::private_key_from_pem(&pair.key_pem).unwrap())
            .unwrap();
        let acceptor = std::sync::Arc::new(acceptor.build());
        for shape in [
            crate::hello_shape::HelloShape::Records,
            crate::hello_shape::HelloShape::Compact,
        ] {
            let (client, server) = tokio::io::duplex(256);
            let server_acceptor = acceptor.clone();
            let server =
                tokio::spawn(async move { tokio_boring::accept(&server_acceptor, server).await });
            let mut connector = SslConnector::builder(SslMethod::tls()).unwrap();
            // Local self-signed test peer only. Production pin verification
            // continues to be installed by masque_h2::build_tls.
            connector.set_verify(SslVerifyMode::NONE);
            let mut shaped = FragmentingStream::new(client, FragmentConfig::disabled());
            shaped.hello_shape = Some(shape);
            let mut client = tokio::time::timeout(
                Duration::from_secs(10),
                tokio_boring::connect(connector.build().configure().unwrap(), "localhost", shaped),
            )
            .await
            .unwrap()
            .unwrap_or_else(|error| panic!("{shape:?} TLS handshake failed: {error}"));
            let mut server = server.await.unwrap().unwrap();
            client.write_all(b"tunnel data").await.unwrap();
            let mut data = [0; 11];
            server.read_exact(&mut data).await.unwrap();
            assert_eq!(&data, b"tunnel data");
        }
    }
}
