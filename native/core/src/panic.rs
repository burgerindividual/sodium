use alloc::string::String;
use core::fmt::Write;
use core::panic::PanicInfo;

pub type PanicHandlerFn = extern "C" fn(data: *const u8, len: i32) -> !;

static mut PANIC_HANDLER: Option<PanicHandlerFn> = None;

pub fn set_panic_handler(panic_handler_fn_ptr: PanicHandlerFn) {
    unsafe {
        PANIC_HANDLER = Some(panic_handler_fn_ptr);
    }
}

#[cfg(not(test))]
#[panic_handler]
fn panic(info: &PanicInfo) -> ! {
    if let Some(handler) = unsafe { PANIC_HANDLER.as_ref() } {
        signal_panic(info, handler)
    } else {
        unsafe { core::hint::unreachable_unchecked() }
    }
}

fn signal_panic(info: &PanicInfo, handler: &PanicHandlerFn) -> ! {
    let mut message = String::new();
    let _ = write!(&mut message, "{}", info);

    (*handler)(message.as_ptr(), message.len() as i32)
}

#[no_mangle]
extern "C" fn rust_eh_personality() {
    // the JVM will complain about this not existing in debug builds, even though it should
    // never be called. the calls are likely not optimized out, even though they aren't hit,
    // so we just make the dynamic linker happy.
}

#[macro_export]
macro_rules! unwrap_debug {
    ($var:expr) => {
        if cfg!(debug_assertions) {
            $var.unwrap()
        } else {
            $var.unwrap_unchecked()
        }
    };
}
