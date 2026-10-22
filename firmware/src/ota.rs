// Over-the-air firmware update over the bridge's own HTTP server.
//
// POST a raw .bin image to http://192.168.4.1:81/ota (AP side) or the STA
// address. The image is streamed straight into the inactive OTA partition;
// on completion the new slot is marked bootable and the bridge reboots.
// Requires the OTA partition layout in partitions.csv (ota_0 / ota_1).
//
// Example:  curl -X POST --data-binary @canscope-bridge.bin http://192.168.4.1:81/ota

use std::io::Read;
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};

use esp_idf_svc::http::server::EspHttpConnection;
use esp_idf_svc::http::Method;
use esp_idf_svc::ota::EspOta;

/// Only one update may run at a time; a second POST gets 409.
static OTA_BUSY: AtomicBool = AtomicBool::new(false);

pub fn register(server: &mut esp_idf_svc::http::server::EspHttpServer<'static>) {
    server
        .fn_handler("/ota", Method::Post, ota_handler)
        .expect("register /ota");
}

fn ota_handler(mut req: esp_idf_svc::http::server::Request<&mut EspHttpConnection<'_>>) -> Result<(), esp_idf_sys::EspError> {
    if OTA_BUSY.swap(true, Ordering::SeqCst) {
        req.into_response(409, Some("Busy"), &[])?;
        return Ok(());
    }
    let result = do_update(&mut req);
    OTA_BUSY.store(false, Ordering::SeqCst);
    result
}

fn do_update(req: &mut esp_idf_svc::http::server::Request<&mut EspHttpConnection<'_>>) -> Result<(), esp_idf_sys::EspError> {
    let mut ota = EspOta::new().map_err(|e| {
        log::error!("OTA init: {e}");
        e
    })?;
    let mut update = match ota.initiate_update() {
        Ok(u) => u,
        Err(e) => {
            log::error!("OTA begin: {e}");
            // best effort: tell the client, then propagate
            req.as_mut().into_response(500, Some("OTA begin failed"), &[])?;
            return Err(e);
        }
    };

    let mut buf = [0u8; 2048];
    let mut total = 0usize;
    loop {
        match req.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if let Err(e) = update.write(&buf[..n]) {
                    log::error!("OTA write at {total}: {e}");
                    update.abort().ok();
                    req.as_mut().into_response(500, Some("OTA write failed"), &[])?;
                    return Err(e);
                }
                total += n;
            }
            Err(e) => {
                log::error!("OTA read: {e}");
                update.abort().ok();
                return Err(e);
            }
        }
    }

    log::info!("OTA image received ({total} bytes), validating…");
    match update.complete() {
        Ok(()) => {
            req.as_mut().into_ok_response()?.write_all(b"OK — rebooting into new firmware")?;
            log::info!("OTA complete, restarting");
            // give the HTTP response a moment to flush
            std::thread::spawn(|| {
                std::thread::sleep(std::time::Duration::from_millis(500));
                unsafe { esp_idf_sys::esp_restart() };
            });
            Ok(())
        }
        Err(e) => {
            log::error!("OTA finalize: {e}");
            req.as_mut().into_response(500, Some("OTA validation failed"), &[])?;
            Err(e)
        }
    }
}
