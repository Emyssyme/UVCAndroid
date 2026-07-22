# Implementation Plan - UI Visibility and Menu Optimization

This plan focuses on making the `EV-L` and `AF-L` buttons on the bottom bar clearly visible in all themes (especially Dark Mode) while keeping them on a single row, and reorganizing the Internal Camera Controls into a compact two-column layout.

## User Review Required

> [!IMPORTANT]
> - I will keep the **Bottom Bar** on a single row as requested.
> - I will use a **thicker border (1.5dp)** and a **fixed high-contrast color** (like a light gray/silver) for the inactive state of `EV-L` and `AF-L` to ensure they are visible on the dark video background.
> - The **Internal Camera Controls** will be refactored into a two-column grid. This will make the menu much shorter and potentially eliminate the need for scrolling on most devices.

## Proposed Changes

### Layouts

#### [MODIFY] [activity_main.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/layout/activity_main.xml)
- **Bottom Bar (`internal_camera_bottom_bar`)**:
    - Keep the single row `LinearLayout`.
    - Set `app:strokeWidth="1.5dp"` for `btnBottomExposureLock` and `btnBottomFocusLock`.
    - Use a dedicated style or color that provides high contrast even when inactive.
- **Internal Controls Panel (`internal_camera_controls`)**:
    - Use a `GridLayout` with 2 columns for the control rows (Zoom, Exposure, Focus, ISO, Shutter, Kelvin).
    - This will place settings side-by-side (e.g., Zoom next to Exposure).

### MainActivity.java

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- Adjust `updateInternalCameraControlPanel()`:
    - Change the `inactiveColor` for lock buttons to a more visible value (e.g., `#BBBBBB` or a brighter theme attribute).
    - Ensure the stroke is always visible.

## Verification Plan

### Manual Verification
- **Bottom Bar**: Check that `EV-L` and `AF-L` borders are clearly visible in Dark Mode before they are pressed.
- **Two-Column Menu**: Verify that the Internal Camera Controls panel shows options in two columns and is much more compact.
