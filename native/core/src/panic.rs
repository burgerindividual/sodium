use std::fmt::Write;
use std::panic::PanicInfo;
use std::string::String;

pub type PanicHandlerFn = extern "C" fn(data: *const u8, len: i32) -> !;

pub fn set_panic_handler(pfn: PanicHandlerFn) {
    std::panic::set_hook(Box::new(move |info| {
        signal_panic(info, pfn);
    }));
}

fn signal_panic(info: &PanicInfo, handler: PanicHandlerFn) -> ! {
    let mut message = String::new();
    write!(&mut message, "{}", info).ok();

    // the java handler should contain
    handler(message.as_ptr(), message.len() as i32)
}
