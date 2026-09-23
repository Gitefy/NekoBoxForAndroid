//! Internal checked cursor for reading wire bytes without panicking.

use crate::error::WireError;

/// Bounded cursor over an in-memory byte slice.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Cursor<'a> {
    slice: &'a [u8],
    offset: usize,
}

#[allow(dead_code)]
impl<'a> Cursor<'a> {
    /// Constructs a new [`Cursor`] starting at offset 0.
    #[must_use]
    pub(crate) const fn new(slice: &'a [u8]) -> Self {
        Self { slice, offset: 0 }
    }

    /// Returns the number of unconsumed bytes remaining in the buffer.
    #[must_use]
    pub(crate) const fn remaining(&self) -> usize {
        self.slice.len() - self.offset
    }

    /// Returns `true` if all bytes have been consumed.
    #[allow(dead_code)]
    #[must_use]
    pub(crate) const fn is_empty(&self) -> bool {
        self.remaining() == 0
    }

    /// Takes exactly `count` bytes from the cursor, advancing the read position.
    ///
    /// # Errors
    ///
    /// Returns [`WireError::Truncated`] if fewer than `count` bytes remain,
    /// or [`WireError::ArithmeticOverflow`] if an integer overflow occurs.
    pub(crate) fn take(&mut self, count: usize) -> Result<&'a [u8], WireError> {
        let new_offset = self
            .offset
            .checked_add(count)
            .ok_or(WireError::ArithmeticOverflow)?;
        if new_offset > self.slice.len() {
            return Err(WireError::Truncated);
        }
        let bytes = &self.slice[self.offset..new_offset];
        self.offset = new_offset;
        Ok(bytes)
    }

    /// Reads a single byte.
    ///
    /// # Errors
    ///
    /// Returns [`WireError::Truncated`] if the cursor is at EOF.
    pub(crate) fn u8(&mut self) -> Result<u8, WireError> {
        let bytes = self.take(1)?;
        Ok(bytes[0])
    }

    /// Reads a 16-bit unsigned integer in big-endian byte order.
    ///
    /// # Errors
    ///
    /// Returns [`WireError::Truncated`] if fewer than 2 bytes remain.
    pub(crate) fn u16_be(&mut self) -> Result<u16, WireError> {
        let bytes = self.take(2)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    /// Reads a 32-bit unsigned integer in big-endian byte order.
    ///
    /// # Errors
    ///
    /// Returns [`WireError::Truncated`] if fewer than 4 bytes remain.
    #[allow(dead_code)]
    pub(crate) fn u32_be(&mut self) -> Result<u32, WireError> {
        let bytes = self.take(4)?;
        Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    /// Reads a 64-bit unsigned integer in big-endian byte order.
    ///
    /// # Errors
    ///
    /// Returns [`WireError::Truncated`] if fewer than 8 bytes remain.
    #[allow(dead_code)]
    pub(crate) fn u64_be(&mut self) -> Result<u64, WireError> {
        let bytes = self.take(8)?;
        Ok(u64::from_be_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        ]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_exceeding_remaining_returns_truncated() {
        let buf = [1, 2, 3];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.remaining(), 3);
        assert_eq!(cursor.take(4), Err(WireError::Truncated));
        assert_eq!(cursor.remaining(), 3);
    }

    #[test]
    fn u8_on_empty_slice_returns_truncated() {
        let buf = [];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.u8(), Err(WireError::Truncated));
    }

    #[test]
    fn u16_be_on_less_than_two_bytes_returns_truncated() {
        let buf = [0x42];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.u16_be(), Err(WireError::Truncated));
    }

    #[test]
    fn u32_be_on_less_than_four_bytes_returns_truncated() {
        let buf = [1, 2, 3];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.u32_be(), Err(WireError::Truncated));
    }

    #[test]
    fn u64_be_on_less_than_eight_bytes_returns_truncated() {
        let buf = [1, 2, 3, 4, 5, 6, 7];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.u64_be(), Err(WireError::Truncated));
    }

    #[test]
    fn sequential_reads_advance_offset_and_report_exact_remaining() {
        let buf = [0x01, 0x02, 0x03, 0xAA, 0xBB, 0xCC];
        let mut cursor = Cursor::new(&buf);
        assert_eq!(cursor.remaining(), 6);
        assert!(!cursor.is_empty());

        assert_eq!(cursor.u8().unwrap(), 0x01);
        assert_eq!(cursor.remaining(), 5);

        assert_eq!(cursor.u16_be().unwrap(), 0x0203);
        assert_eq!(cursor.remaining(), 3);

        let slice = cursor.take(3).unwrap();
        assert_eq!(slice, &[0xAA, 0xBB, 0xCC]);
        assert_eq!(cursor.remaining(), 0);
        assert!(cursor.is_empty());

        assert_eq!(cursor.u8(), Err(WireError::Truncated));
    }
}
