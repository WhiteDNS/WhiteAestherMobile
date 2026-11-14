//! Out-of-band ClientHello desync for the WARP API bootstrap.
//!
//! Adapted from the AGPL-3.0 Aether fork in Floxu1/UAC-SNI-Spoofer-Android
//! (`core/aether/src/oob.rs`, commit 200de3b1f029839d2860042680afebf8817c3a90).
//! Only the socket-level send of the first bytes is taken; the TLS session,
//! certificate checks and the API race stay this fork's.
//!
//! Each method sends a short prefix of the ClientHello in a way an on-path
//! inspector cannot reassemble, then the rest normally:
//!
//! - with TTL=1, so the prefix dies at the first hop and the inspector never
//!   sees an in-order hello; the kernel retransmits it at the restored TTL;
//! - as one TCP urgent byte (MSG_OOB), which the server's stack strips because
//!   out-of-band data is not inlined, so the server reads exactly the hello.
//!
//! Only ordinary socket options are used: no raw sockets and no root. Nothing
//! here weakens TLS. The handshake that follows is verified as on every route.

use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU8, Ordering};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OobMethod {
    /// TTL=1, `hello[0]` as urgent, TTL restored, pause, then the full hello.
    DisoobByte0Ttl1,
    /// TTL=1, `hello[..sni+5]` normally, `hello[sni+5]` as urgent, TTL
    /// restored, pause, then `hello[sni+5..]`.
    OobInSni5Ttl1,
    /// `hello[0]` as urgent without touching the TTL, pause, then the full hello.
    OobByte0NoTtl,
    /// TTL=1, `hello[0]` sent normally (no urgent pointer), TTL restored,
    /// pause, then the full hello. The dying byte never reaches the server.
    DisoobByte0Ttl1Plain,
    /// Like `DisoobByte0Ttl1`, then `hello[0]` as its own normal segment and
    /// `hello[1..]` after the pause, for Linux send coalescing.
    DisoobByte0Ttl1Split,
}

/// The order the reference fork found working on Android, the platform this
/// build ships to. Desktop tries only the three methods its probe proved.
#[cfg(target_os = "android")]
pub const METHOD_ORDER: &[OobMethod] = &[
    OobMethod::DisoobByte0Ttl1,
    OobMethod::OobInSni5Ttl1,
    OobMethod::OobByte0NoTtl,
    OobMethod::DisoobByte0Ttl1Plain,
    OobMethod::DisoobByte0Ttl1Split,
];
#[cfg(not(target_os = "android"))]
pub const METHOD_ORDER: &[OobMethod] = &[
    OobMethod::DisoobByte0Ttl1,
    OobMethod::OobInSni5Ttl1,
    OobMethod::OobByte0NoTtl,
];

impl OobMethod {
    pub fn name(self) -> &'static str {
        match self {
            Self::DisoobByte0Ttl1 => "oob-disorder-byte0",
            Self::OobInSni5Ttl1 => "oob-disorder-in-sni",
            Self::OobByte0NoTtl => "oob-byte0",
            Self::DisoobByte0Ttl1Plain => "oob-disorder-plain",
            Self::DisoobByte0Ttl1Split => "oob-disorder-split",
        }
    }

    fn uses_ttl1(self) -> bool {
        !matches!(self, Self::OobByte0NoTtl)
    }

    fn urgent(self) -> bool {
        !matches!(self, Self::DisoobByte0Ttl1Plain)
    }

    /// Wait between the prefix and the rest of the hello.
    pub fn pause(self) -> Duration {
        if self.uses_ttl1() {
            Duration::from_millis(50)
        } else {
            Duration::from_millis(30)
        }
    }

    /// Where the urgent byte goes: byte 0, or five bytes into the server name.
    pub fn split_offset(self, hello: &[u8]) -> usize {
        match self {
            Self::OobInSni5Ttl1 => crate::fragment::sni_host_range(hello)
                .map(|(start, _)| start + 5)
                .filter(|offset| *offset < hello.len())
                .unwrap_or(0),
            _ => 0,
        }
    }
}

/// The last method an API call went through, tried first next time. 255 is none.
static PREFERRED: AtomicU8 = AtomicU8::new(u8::MAX);

pub fn ordered_methods() -> Vec<OobMethod> {
    let mut out = Vec::with_capacity(METHOD_ORDER.len());
    if let Some(first) = METHOD_ORDER.get(PREFERRED.load(Ordering::Relaxed) as usize) {
        out.push(*first);
    }
    for method in METHOD_ORDER {
        if !out.contains(method) {
            out.push(*method);
        }
    }
    out
}

pub fn remember(method: OobMethod) {
    if let Some(index) = METHOD_ORDER.iter().position(|m| *m == method) {
        PREFERRED.store(index as u8, Ordering::Relaxed);
    }
}

#[cfg(windows)]
const FALLBACK_TTL: u32 = 128;
#[cfg(not(windows))]
const FALLBACK_TTL: u32 = 64;

fn current_ttl(sock: &socket2::Socket, v6: bool) -> u32 {
    let value = if v6 {
        sock.unicast_hops_v6()
    } else {
        sock.ttl_v4()
    };
    match value {
        Ok(ttl) if ttl > 0 && ttl < 256 => ttl,
        _ => FALLBACK_TTL,
    }
}

fn set_ttl(sock: &socket2::Socket, v6: bool, ttl: u32) -> io::Result<()> {
    if v6 {
        sock.set_unicast_hops_v6(ttl)
    } else {
        sock.set_ttl_v4(ttl)
    }
}

/// A fresh socket takes a short prefix whole; anything less is a failed method.
fn send_whole(sock: &socket2::Socket, data: &[u8], urgent: bool) -> io::Result<()> {
    if data.is_empty() {
        return Ok(());
    }
    let sent = if urgent {
        sock.send_out_of_band(data)?
    } else {
        sock.send(data)?
    };
    if sent != data.len() {
        return Err(io::Error::other(format!(
            "oob: short send ({sent} of {} bytes)",
            data.len()
        )));
    }
    Ok(())
}

/// Sends the desync prefix of `hello` on `sock`, a handle to the connection's
/// own socket. Returns the offset from which the caller sends the rest of the
/// hello normally, after `method.pause()`.
///
/// The TTL is always put back and read back: a later segment leaving at TTL 1
/// would be a connection that hangs after its handshake.
pub fn send_prefix(sock: &socket2::Socket, hello: &[u8], method: OobMethod) -> io::Result<usize> {
    if hello.len() < 2 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "oob: hello too short",
        ));
    }
    let v6 = matches!(sock.peer_addr()?.as_socket(), Some(SocketAddr::V6(_)));
    let _ = sock.set_tcp_nodelay(true);
    #[cfg(not(windows))]
    let _ = sock.set_out_of_band_inline(false);
    let offset = method.split_offset(hello);
    let saved = current_ttl(sock, v6);
    if method.uses_ttl1() {
        set_ttl(sock, v6, 1)?;
        if current_ttl(sock, v6) != 1 {
            let _ = set_ttl(sock, v6, saved);
            return Err(io::Error::other("oob: the TTL could not be lowered"));
        }
    }
    let sent = (|| {
        send_whole(sock, &hello[..offset], false)?;
        send_whole(sock, &hello[offset..offset + 1], method.urgent())
    })();
    if method.uses_ttl1() {
        let restored = set_ttl(sock, v6, saved);
        sent?;
        restored?;
        let now = current_ttl(sock, v6);
        if now != saved {
            return Err(io::Error::other(format!(
                "oob: the TTL stayed at {now}, not {saved}"
            )));
        }
    } else {
        sent?;
    }
    let resume = if matches!(method, OobMethod::DisoobByte0Ttl1Split) {
        send_whole(sock, &hello[..1], false)?;
        1
    } else {
        offset
    };
    log::debug!(
        "[api bootstrap] {}: {} byte hello, urgent byte at {offset}, resume at {resume}",
        method.name(),
        hello.len()
    );
    Ok(resume)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fragment::{FragmentConfig, FragmentingStream};

    fn hello() -> Vec<u8> {
        let host = b"api.cloudflareclient.com";
        let mut sni = vec![0x00];
        sni.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni.extend_from_slice(host);
        let mut list = (sni.len() as u16).to_be_bytes().to_vec();
        list.extend_from_slice(&sni);
        let mut ext = vec![0x00, 0x00];
        ext.extend_from_slice(&(list.len() as u16).to_be_bytes());
        ext.extend_from_slice(&list);
        let mut body = vec![0x03, 0x03];
        body.extend_from_slice(&[7u8; 32]);
        body.push(0x00);
        body.extend_from_slice(&[0x00, 0x02, 0x13, 0x01, 0x01, 0x00]);
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
    fn the_in_sni_method_splits_inside_the_server_name_and_the_others_at_byte_zero() {
        let hello = hello();
        let (start, _) = crate::fragment::sni_host_range(&hello).unwrap();
        assert_eq!(OobMethod::OobInSni5Ttl1.split_offset(&hello), start + 5);
        assert_eq!(OobMethod::DisoobByte0Ttl1.split_offset(&hello), 0);
        assert_eq!(OobMethod::OobInSni5Ttl1.split_offset(b"\x16\x03\x01xx"), 0);
    }

    #[test]
    fn the_last_working_method_goes_first_and_none_is_lost() {
        remember(METHOD_ORDER[METHOD_ORDER.len() - 1]);
        let order = ordered_methods();
        assert_eq!(order[0], METHOD_ORDER[METHOD_ORDER.len() - 1]);
        assert_eq!(order.len(), METHOD_ORDER.len());
        PREFERRED.store(u8::MAX, Ordering::Relaxed);
        assert_eq!(ordered_methods(), METHOD_ORDER.to_vec());
    }

    async fn delivered(method: OobMethod) -> Vec<u8> {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut got = Vec::new();
            stream.read_to_end(&mut got).await.unwrap();
            got
        });
        let tcp = tokio::net::TcpStream::connect(address).await.unwrap();
        let handle = socket2::SockRef::from(&tcp).try_clone().unwrap();
        let before = current_ttl(&handle, false);
        let mut stream =
            FragmentingStream::new(tcp, FragmentConfig::disabled()).with_oob(method, handle);
        stream.write_all(&hello()).await.unwrap();
        stream.write_all(b"after").await.unwrap();
        stream.shutdown().await.unwrap();
        let check = socket2::SockRef::from(stream.get_ref())
            .try_clone()
            .unwrap();
        assert_eq!(
            current_ttl(&check, false),
            before,
            "{} left the TTL changed",
            method.name()
        );
        drop(stream);
        server.await.unwrap()
    }

    #[tokio::test]
    async fn every_urgent_method_delivers_exactly_the_hello_and_restores_the_ttl() {
        let mut expected = hello();
        expected.extend_from_slice(b"after");
        // Loopback never drops a TTL-1 segment, so the plain method, whose
        // first byte is meant to die on the way, would arrive twice here.
        for method in METHOD_ORDER.iter().filter(|m| m.urgent()) {
            assert_eq!(delivered(*method).await, expected, "{}", method.name());
        }
    }
}
