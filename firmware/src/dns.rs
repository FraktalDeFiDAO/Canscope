// Minimal captive-portal DNS responder: every A query is answered with the
// ESP32's AP address, so phones pop the "Sign in to network" portal and land
// on the dashboard. Runs on UDP :53. Deliberately tiny — not a real resolver.

use std::net::UdpSocket;

pub fn run(ap_ip: [u8; 4]) {
    let sock = match UdpSocket::bind(("0.0.0.0", 53)) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("DNS bind failed: {e}");
            return;
        }
    };
    log::info!("Captive DNS on :53 → {}.{}.{}.{}", ap_ip[0], ap_ip[1], ap_ip[2], ap_ip[3]);

    let mut buf = [0u8; 512];
    loop {
        let (n, peer) = match sock.recv_from(&mut buf) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if n < 12 { continue; }
        let req = &buf[..n];

        // Build response: copy header, set flags QR=1 AA=1, RD copied, RA=0.
        let mut resp: Vec<u8> = Vec::with_capacity(n + 16);
        resp.extend_from_slice(&req[0..2]);          // ID
        let rd = req[2] & 0x01;
        resp.extend_from_slice(&[0x80 | 0x04 | rd, 0x00]); // QR | AA | RD
        resp.extend_from_slice(&req[4..6]);          // QDCOUNT
        resp.extend_from_slice(&[0x00, 0x01, 0x00, 0x00]); // ANCOUNT=1, NS=AR=0
        // Question section: copy verbatim, find its end (walk the qname labels).
        let mut i = 12;
        while i < n && req[i] != 0 { i += 1 + req[i] as usize; }
        let qend = (i + 5).min(n);                   // root label + QTYPE + QCLASS
        resp.extend_from_slice(&req[12..qend]);
        // Answer: name pointer to question, A record, TTL 60, the AP address.
        resp.extend_from_slice(&[0xC0, 0x0C]);
        resp.extend_from_slice(&[0x00, 0x01]);       // TYPE A
        resp.extend_from_slice(&[0x00, 0x01]);       // CLASS IN
        resp.extend_from_slice(&[0x00, 0x00, 0x00, 0x3C]); // TTL 60s
        resp.extend_from_slice(&[0x00, 0x04]);       // RDLENGTH
        resp.extend_from_slice(&ap_ip);
        let _ = sock.send_to(&resp, peer);
    }
}
