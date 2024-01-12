use alloc::boxed::Box;
use core::mem::MaybeUninit;
use core::ptr::{self, addr_of_mut};

use crate::mem::InitDefaultInPlace;

pub struct ArrayDeque<T, const CAPACITY: usize> {
    head: usize,
    tail: usize,
    elements: [MaybeUninit<T>; CAPACITY],
}

impl<T, const CAPACITY: usize> ArrayDeque<T, CAPACITY> {
    pub fn push(&mut self, value: T) {
        self.set_tail_element(value);

        self.tail += 1;
    }

    pub fn push_conditionally(&mut self, value: T, cond: bool) {
        self.set_tail_element(value);

        self.tail += if cond { 1 } else { 0 };
    }

    fn set_tail_element(&mut self, value: T) {
        unsafe {
            *self.elements.get_mut(self.tail).unwrap_unchecked() = MaybeUninit::new(value);
        }
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
            elements: unsafe { MaybeUninit::<[MaybeUninit<T>; CAPACITY]>::uninit().assume_init() },
        }
    }
}

impl<T, const CAPACITY: usize> InitDefaultInPlace for *mut ArrayDeque<T, CAPACITY> {
    fn init_default_in_place(self) {
        unsafe {
            addr_of_mut!((*self).head).write(0);
            addr_of_mut!((*self).tail).write(0);
            // skip initialization of data array because the contents don't
            // matter
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

// TODO: validate that the capacity is smaller than u32::MAX
#[repr(C)]
pub struct CInlineVec<T, const CAPACITY: usize> {
    count: u32,
    data: [MaybeUninit<T>; CAPACITY],
}

impl<T, const CAPACITY: usize> CInlineVec<T, CAPACITY> {
    pub fn push(&mut self, value: T) {
        self.set_top_element(value);
        self.count += 1;
    }

    pub fn push_conditionally(&mut self, value: T, cond: bool) {
        self.set_top_element(value);
        self.count += if cond { 1 } else { 0 };
    }

    fn set_top_element(&mut self, value: T) {
        unsafe {
            *self.data.get_mut(self.count as usize).unwrap_unchecked() = MaybeUninit::new(value);
        }
    }

    pub fn clear(&mut self) {
        unsafe {
            for i in 0..self.count as usize {
                self.data.get_mut(i).unwrap_unchecked().assume_init_drop();
            }
        }

        self.count = 0;
    }

    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    pub fn element_count(&self) -> u32 {
        self.count
    }

    /// # Safety
    /// The vec must not be empty before calling this function.
    pub fn pop(&mut self) -> T {
        self.count -= 1;
        unsafe {
            self.data
                .get(self.count as usize)
                .unwrap_unchecked()
                .assume_init_read()
        }
    }

    pub fn get_slice(&self) -> &[T] {
        // SAFETY: count shouldn't ever be able to be incremented past LEN, and the
        // contents should be initialized
        unsafe {
            MaybeUninit::slice_assume_init_ref(
                self.data.get(0..self.count as usize).unwrap_unchecked(),
            )
        }
    }

    pub fn get_slice_mut(&mut self) -> &mut [T] {
        // SAFETY: count shouldn't ever be able to be incremented past LEN, and the
        // contents should be initialized
        unsafe {
            MaybeUninit::slice_assume_init_mut(
                self.data.get_mut(0..self.count as usize).unwrap_unchecked(),
            )
        }
    }
}

impl<T, const CAPACITY: usize> Default for CInlineVec<T, CAPACITY> {
    fn default() -> Self {
        Self {
            data: unsafe { MaybeUninit::<[MaybeUninit<T>; CAPACITY]>::uninit().assume_init() },
            count: 0,
        }
    }
}

impl<T, const CAPACITY: usize> InitDefaultInPlace for *mut CInlineVec<T, CAPACITY> {
    fn init_default_in_place(self) {
        unsafe {
            addr_of_mut!((*self).count).write(0);
            // skip initialization of data array because the contents don't
            // matter
        }
    }
}

impl<T: Copy, const CAPACITY: usize> Clone for CInlineVec<T, CAPACITY> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T: Copy, const CAPACITY: usize> Copy for CInlineVec<T, CAPACITY> {}
