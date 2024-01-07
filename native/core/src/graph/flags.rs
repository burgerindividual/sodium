#[repr(u8)]
pub enum SectionFlag {
    HasBlockGeometry = 0,
    HasBlockEntities = 1,
    HasAnimatedSprites = 2,
}

#[repr(transparent)]
#[derive(Clone, Copy)]
pub struct SectionFlagSet(u8);

impl SectionFlagSet {
    pub const NONE: Self = Self(0);

    pub const fn from(packed: u8) -> Self {
        Self(packed)
    }

    pub const fn contains(&self, flag: SectionFlag) -> bool {
        (self.0 & (1 << flag as u8)) != 0
    }
}

impl Default for SectionFlagSet {
    fn default() -> Self {
        Self::NONE
    }
}
