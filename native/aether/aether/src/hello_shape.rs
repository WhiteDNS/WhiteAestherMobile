//! TLS record splitting followed by bounded TCP writes. The TLS handshake
//! bytes are preserved; only their record and write boundaries change.
use std::{
    io,
    pin::Pin,
    task::{Context, Poll},
    time::Duration,
};
use tokio::io::AsyncWrite;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HelloShape {
    Records,
    Compact,
}

impl HelloShape {
    pub fn from_env() -> Option<Self> {
        match std::env::var("AETHER_HELLO_SHAPE").as_deref() {
            Ok("records") => Some(Self::Records),
            Ok("compact") => Some(Self::Compact),
            _ => None,
        }
    }

    /// Accept complete ClientHello records only. A partial or unrelated TLS
    /// write is left untouched, including any records following ClientHello.
    pub fn encode(self, input: &[u8]) -> Option<Vec<u8>> {
        if input.len() < 9 || input[0] != 22 || input[5] != 1 {
            return None;
        }
        let end = 5 + usize::from(u16::from_be_bytes([input[3], input[4]]));
        if end > input.len() {
            return None;
        }
        let hello_len = 4
            + ((usize::from(input[6]) << 16)
                | (usize::from(input[7]) << 8)
                | usize::from(input[8]));
        if hello_len != end - 5 {
            return None;
        }
        let lengths = match self {
            Self::Records => [5, 94, 1],
            Self::Compact => [0, 104, 1],
        };
        let limit = match self {
            Self::Records => 355,
            Self::Compact => 1024,
        };
        let data = &input[5..end];
        let mut out = Vec::with_capacity(input.len() + 5 * limit.min(data.len() + 1));
        let mut offset = 0;
        for index in 0..limit {
            let length = if index + 1 == limit {
                data.len() - offset
            } else {
                lengths[index.min(2)].min(data.len() - offset)
            };
            out.extend_from_slice(&input[..3]);
            out.extend_from_slice(&(length as u16).to_be_bytes());
            out.extend_from_slice(&data[offset..offset + length]);
            offset += length;
            if offset == data.len() {
                break;
            }
        }
        out.extend_from_slice(&input[end..]);
        Some(out)
    }

    pub fn pending(self, bytes: Vec<u8>) -> PendingHello {
        let (first, limit) = match self {
            Self::Records => (109, 355),
            Self::Compact => (114, 11),
        };
        let boundary = first.min(bytes.len());
        PendingHello {
            bytes,
            offset: 0,
            boundary,
            chunks: 0,
            limit,
            delay: None,
        }
    }
}

/// Rewritten bytes awaiting transmission. AsyncWrite reports completion only
/// after they drain; a pending BIO write retains its original input for retry.
pub struct PendingHello {
    bytes: Vec<u8>,
    offset: usize,
    boundary: usize,
    chunks: usize,
    limit: usize,
    delay: Option<Pin<Box<tokio::time::Sleep>>>,
}

impl PendingHello {
    pub fn poll_drain<S: AsyncWrite + Unpin>(
        &mut self,
        stream: &mut S,
        cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        use std::future::Future;
        while self.offset < self.bytes.len() {
            if let Some(delay) = &mut self.delay {
                if delay.as_mut().poll(cx).is_pending() {
                    return Poll::Pending;
                }
                self.delay = None;
            }
            let written = std::task::ready!(
                Pin::new(&mut *stream).poll_write(cx, &self.bytes[self.offset..self.boundary])
            )?;
            if written == 0 {
                return Poll::Ready(Err(io::ErrorKind::WriteZero.into()));
            }
            self.offset += written;
            if self.offset == self.boundary && self.offset < self.bytes.len() {
                self.chunks += 1;
                self.boundary = if self.chunks + 1 >= self.limit {
                    self.bytes.len()
                } else {
                    self.offset + 1
                };
                self.delay = Some(Box::pin(tokio::time::sleep(Duration::from_millis(1))));
            }
        }
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hello(payload: usize) -> Vec<u8> {
        let mut record = vec![22, 3, 1];
        record.extend_from_slice(&((payload + 4) as u16).to_be_bytes());
        record.extend_from_slice(&[1, 0, (payload >> 8) as u8, payload as u8]);
        record.extend((0..payload).map(|n| n as u8));
        record
    }

    #[test]
    fn splitting_preserves_every_handshake_byte_and_following_records() {
        for shape in [HelloShape::Records, HelloShape::Compact] {
            for size in [1, 100, 1500, 16000] {
                let original = hello(size);
                let suffix = [20, 3, 3, 0, 1, 1];
                let mut input = original.clone();
                input.extend_from_slice(&suffix);
                let out = shape.encode(&input).unwrap();
                let mut at = 0;
                let mut decoded = Vec::new();
                while decoded.len() < original.len() - 5 {
                    assert_eq!(&out[at..at + 3], &original[..3]);
                    let n = usize::from(u16::from_be_bytes([out[at + 3], out[at + 4]]));
                    decoded.extend_from_slice(&out[at + 5..at + 5 + n]);
                    at += 5 + n;
                }
                assert_eq!(decoded, original[5..]);
                assert_eq!(out[at..], suffix);
            }
        }
    }

    #[test]
    fn incomplete_hello_and_application_data_are_never_rewritten() {
        let packet = hello(100);
        for length in 0..packet.len() {
            assert!(HelloShape::Records.encode(&packet[..length]).is_none());
        }
        let mut packet = packet;
        packet[0] = 23;
        assert!(HelloShape::Compact.encode(&packet).is_none());
    }
}
