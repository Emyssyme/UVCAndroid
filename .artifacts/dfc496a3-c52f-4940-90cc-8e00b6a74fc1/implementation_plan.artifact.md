# Implementation Plan - Fix Invisible Lock Buttons and Menu Text

This plan addresses the invisibility of `EV-L` and `AF-L` buttons when inactive, and persistent visibility issues with menu items in Light Mode.

## Proposed Changes

### [Component: Java Logic]

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- In `updateInternalCameraControlPanel()`, change the `activeColor` to use `?attr/colorPrimary` instead of `?attr/colorSecondary` (Purple 500 is more visible than Teal 200 on white).
- Use `?attr/colorOnSurface` or `?android:attr/textColorPrimary` for `inactiveColor`.
- Ensure `activeBackground` uses `colorPrimary` with alpha.
- Explicitly set `inactiveBackground` to `Color.TRANSPARENT`.
- Verify that `setStrokeColor` and `setTextColor` are called correctly for both active and inactive states.

### [Component: Themes and Styles]

#### [MODIFY] [themes.xml (app)](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/themes.xml)
- Update `Theme.Usbvideo.PopupOverlay` to inherit from `ThemeOverlay.MaterialComponents.DayNight` to ensure better compatibility with the base Material theme.
- Ensure `colorOnSurface` is correctly defined if necessary (usually inherited).

#### [MODIFY] [styles.xml (app)](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/styles.xml)
- Update `MenuTextAppearance` to use `?android:attr/textColorPrimary` as a fallback if `?attr/colorOnSurface` is causing issues.

## Verification Plan

### Manual Verification
- Deploy in Light Mode.
- Verify `EV-L` and `AF-L` are visible (black or purple outline/text) when NOT activated.
- Verify the overflow menu items are black on a light background.
- Toggle to Dark Mode and verify everything is still visible.
