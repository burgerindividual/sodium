use alloc::string::String;
use core::fmt::Write;
use core::panic::PanicInfo;

pub type PanicHandlerFn = extern "C" fn(data: *const u8, len: i32) -> !;

static mut PANIC_HANDLER: Option<PanicHandlerFn> = None;

pub fn set_panic_handler(pfn: PanicHandlerFn) {
    unsafe {
        PANIC_HANDLER = Some(pfn);
    }
}

// rust analyzer cries if this isn't here
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
    write!(&mut message, "{}", info).ok();

    (*handler)(message.as_ptr(), message.len() as i32)
}
