use std::mem::MaybeUninit;
use std::ptr;

pub struct ArrayDeque<T, const CAPACITY: usize> {
    head: usize,
    tail: usize,
    elements: [MaybeUninit<T>; CAPACITY],
}

impl<T, const CAPACITY: usize> ArrayDeque<T, CAPACITY> {
    pub fn push(&mut self, value: T) {
        self.elements[self.tail] = MaybeUninit::new(value);
        self.tail += 1;
    }

    pub unsafe fn push_conditionally_unchecked(&mut self, value: T, cond: bool) {
        let holder = self.elements.get_mut(self.tail).unwrap_unchecked();
        *holder = MaybeUninit::new(value);

        self.tail += if cond { 1 } else { 0 };
    }

    pub fn pop(&mut self) -> Option<&T> {
        if self.is_empty() {
            return None;
        }

        // the unchecked unwrap should be fine, because if we read past the array, it
        // would've already been a problem when we pushed an element past the
        // array.
        let value = unsafe {
            MaybeUninit::assume_init_ref(self.elements.get(self.head).unwrap_unchecked())
        };
        self.head += 1;

        Some(value)
    }

    pub fn reset(&mut self) {
        self.head = 0;
        self.tail = 0;
    }

    pub fn is_empty(&self) -> bool {
        self.head == self.tail
    }
}

impl<T, const CAPACITY: usize> Default for ArrayDeque<T, CAPACITY> {
    fn default() -> Self {
        Self {
            head: 0,
            tail: 0,

            // MaybeUninit::uninit_array::<CAPACITY>()
            // https://github.com/rust-lang/rust/issues/96097
            elements: unsafe { MaybeUninit::<[MaybeUninit<T>; CAPACITY]>::uninit().assume_init() },
        }
    }
}

#[repr(C)]
pub struct CVec<T> {
    count: u32,
    data: *mut T,
}

impl<T> CVec<T> {
    pub fn from_boxed_slice(data: Box<[T]>) -> Self {
        CVec {
            count: data.len().try_into().expect("len is not a valid u32"),
            data: if data.len() == 0 {
                ptr::null_mut()
            } else {
                Box::leak(data).as_mut_ptr()
            },
        }
    }
}

#[repr(C)]
pub struct CInlineVec<T, const LEN: usize> {
    data: [MaybeUninit<T>; LEN],
    count: usize,
}

impl<T, const LEN: usize> CInlineVec<T, LEN> {
    pub fn push(&mut self, value: T) {
        unsafe {
            *self.data.get_mut(self.count).unwrap_unchecked() = MaybeUninit::new(value);
        }
        self.count += 1;
    }

    pub fn clear(&mut self) {
        unsafe {
            for i in 0..self.count {
                self.data.get_mut(i).unwrap_unchecked().assume_init_drop();
            }
        }

        self.count = 0;
    }

    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// # Safety
    /// The vec must not be empty before calling this function.
    pub fn pop(&mut self) -> T {
        self.count -= 1;
        unsafe {
            self.data
                .get(self.count)
                .unwrap_unchecked()
                .assume_init_read()
        }
    }

    pub fn get_slice(&self) -> &[T] {
        // SAFETY: count shouldn't ever be able to be incremented past LEN, and the
        // contents should be initialized
        unsafe {
            MaybeUninit::slice_assume_init_ref(self.data.get(0..(self.count)).unwrap_unchecked())
        }
    }

    pub fn get_slice_mut(&mut self) -> &mut [T] {
        // SAFETY: count shouldn't ever be able to be incremented past LEN, and the
        // contents should be initialized
        unsafe {
            MaybeUninit::slice_assume_init_mut(
                self.data.get_mut(0..(self.count)).unwrap_unchecked(),
            )
        }
    }
}

impl<T, const LEN: usize> Default for CInlineVec<T, LEN> {
    fn default() -> Self {
        Self {
            data: unsafe { MaybeUninit::<[MaybeUninit<T>; LEN]>::uninit().assume_init() },
            count: 0,
        }
    }
}

impl<T: Copy, const LEN: usize> Clone for CInlineVec<T, LEN> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T: Copy, const LEN: usize> Copy for CInlineVec<T, LEN> {}
