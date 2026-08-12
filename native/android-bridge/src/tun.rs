#[cfg(target_os = "android")]
mod platform {
    use std::fs::File;
    use std::io::{self, Read, Write};
    use std::os::fd::{AsRawFd, FromRawFd, RawFd};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::thread::{self, JoinHandle};

    use tokio::sync::mpsc;

    pub struct TunPump {
        cancelled: Arc<AtomicBool>,
        reader: Option<JoinHandle<()>>,
        writer: Option<JoinHandle<()>>,
    }

    impl TunPump {
        pub fn start(
            tun_fd: RawFd,
            device_packets: mpsc::Sender<Vec<u8>>,
            mut tunnel_packets: mpsc::Receiver<Vec<u8>>,
        ) -> io::Result<Self> {
            let writer_fd = unsafe { libc::dup(tun_fd) };
            if writer_fd < 0 {
                unsafe { libc::close(tun_fd) };
                return Err(io::Error::last_os_error());
            }

            let reader_file = unsafe { File::from_raw_fd(tun_fd) };
            let writer_file = unsafe { File::from_raw_fd(writer_fd) };
            set_nonblocking(reader_file.as_raw_fd())?;
            let cancelled = Arc::new(AtomicBool::new(false));

            let reader_cancelled = cancelled.clone();
            let reader = thread::Builder::new()
                .name("whiteaesther-tun-read".into())
                .spawn(move || {
                    let mut tun = reader_file;
                    let mut buffer = vec![0_u8; 65_535];
                    while !reader_cancelled.load(Ordering::Relaxed) {
                        if !poll_fd(tun.as_raw_fd(), libc::POLLIN, 250) {
                            continue;
                        }
                        match tun.read(&mut buffer) {
                            Ok(0) => break,
                            Ok(length) => {
                                if device_packets
                                    .blocking_send(buffer[..length].to_vec())
                                    .is_err()
                                {
                                    break;
                                }
                            }
                            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                            Err(_) => break,
                        }
                    }
                })?;

            let writer_cancelled = cancelled.clone();
            let writer = match thread::Builder::new()
                .name("whiteaesther-tun-write".into())
                .spawn(move || {
                    let mut tun = writer_file;
                    while let Some(packet) = tunnel_packets.blocking_recv() {
                        if writer_cancelled.load(Ordering::Relaxed)
                            || write_packet(&mut tun, &packet, &writer_cancelled).is_err()
                        {
                            break;
                        }
                    }
                }) {
                Ok(writer) => writer,
                Err(error) => {
                    cancelled.store(true, Ordering::Relaxed);
                    let _ = reader.join();
                    return Err(error);
                }
            };

            Ok(Self {
                cancelled,
                reader: Some(reader),
                writer: Some(writer),
            })
        }

        pub fn stop(mut self) {
            self.cancelled.store(true, Ordering::Relaxed);
            if let Some(reader) = self.reader.take() {
                let _ = reader.join();
            }
            if let Some(writer) = self.writer.take() {
                let _ = writer.join();
            }
        }
    }

    fn set_nonblocking(fd: RawFd) -> io::Result<()> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    fn poll_fd(fd: RawFd, events: libc::c_short, timeout_ms: i32) -> bool {
        let mut descriptor = libc::pollfd {
            fd,
            events,
            revents: 0,
        };
        unsafe {
            libc::poll(&mut descriptor, 1, timeout_ms) > 0 && descriptor.revents & events != 0
        }
    }

    fn write_packet(tun: &mut File, packet: &[u8], cancelled: &AtomicBool) -> io::Result<()> {
        let mut offset = 0;
        while offset < packet.len() && !cancelled.load(Ordering::Relaxed) {
            if !poll_fd(tun.as_raw_fd(), libc::POLLOUT, 250) {
                continue;
            }
            match tun.write(&packet[offset..]) {
                Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
                Ok(length) => offset += length,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use platform::TunPump;

#[cfg(not(target_os = "android"))]
pub struct TunPump;

#[cfg(not(target_os = "android"))]
impl TunPump {
    pub fn start(
        _tun_fd: i32,
        _device_packets: tokio::sync::mpsc::Sender<Vec<u8>>,
        _tunnel_packets: tokio::sync::mpsc::Receiver<Vec<u8>>,
    ) -> std::io::Result<Self> {
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "TUN is only available on Android",
        ))
    }

    pub fn stop(self) {}
}
