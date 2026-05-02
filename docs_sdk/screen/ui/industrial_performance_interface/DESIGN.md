---
name: Industrial Performance Interface
colors:
  surface: '#161308'
  surface-dim: '#161308'
  surface-bright: '#3d392c'
  surface-container-lowest: '#110e05'
  surface-container-low: '#1f1b10'
  surface-container: '#231f14'
  surface-container-high: '#2e2a1e'
  surface-container-highest: '#393528'
  on-surface: '#eae2cf'
  on-surface-variant: '#d0c6ab'
  inverse-surface: '#eae2cf'
  inverse-on-surface: '#343024'
  outline: '#999077'
  outline-variant: '#4d4732'
  surface-tint: '#e9c400'
  primary: '#fff6df'
  on-primary: '#3a3000'
  primary-container: '#ffd700'
  on-primary-container: '#705e00'
  inverse-primary: '#705d00'
  secondary: '#bdf4ff'
  on-secondary: '#00363d'
  secondary-container: '#00e3fd'
  on-secondary-container: '#00616d'
  tertiary: '#defcff'
  on-tertiary: '#00363a'
  tertiary-container: '#00f1ff'
  on-tertiary-container: '#006a70'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffe16d'
  primary-fixed-dim: '#e9c400'
  on-primary-fixed: '#221b00'
  on-primary-fixed-variant: '#544600'
  secondary-fixed: '#9cf0ff'
  secondary-fixed-dim: '#00daf3'
  on-secondary-fixed: '#001f24'
  on-secondary-fixed-variant: '#004f58'
  tertiary-fixed: '#79f5ff'
  tertiary-fixed-dim: '#00dbe8'
  on-tertiary-fixed: '#002022'
  on-tertiary-fixed-variant: '#004f54'
  background: '#161308'
  on-background: '#eae2cf'
  surface-variant: '#393528'
typography:
  timer-xl:
    fontFamily: Space Grotesk
    fontSize: 72px
    fontWeight: '700'
    lineHeight: 80px
    letterSpacing: -0.02em
  data-lg:
    fontFamily: Space Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '500'
    lineHeight: 28px
  label-caps:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '800'
    lineHeight: 16px
    letterSpacing: 0.1em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  margin-edge: 24px
  gutter: 16px
  touch-target-min: 56px
  stack-sm: 12px
  stack-md: 24px
  stack-lg: 40px
---

## Brand & Style

The design system is engineered for mission-critical race timing environments where speed, accuracy, and environmental resilience are paramount. The aesthetic is rooted in **High-Contrast / Bold** design, drawing inspiration from industrial instrumentation and safety equipment. It prioritizes "functional aggression"—every pixel serves a purpose in ensuring data is legible at a glance from five feet away in harsh sunlight.

The brand personality is professional and rugged, evoking the reliability of heavy-duty machinery. By utilizing a dark-mode-first approach, the system minimizes eye strain during long shifts and preserves battery life on hardware like the Chainway C72. The emotional response is one of absolute confidence; the UI feels like a high-performance tool rather than a consumer application.

## Colors

This design system utilizes a high-visibility palette optimized for the outdoor conditions of race timing.

- **Primary (Safety Yellow):** Used for the most critical interactive elements and primary call-to-actions. It provides maximum luminance against the deep charcoal background.
- **Background & Surfaces:** A "True Black" (#0A0A0A) background is used to maximize contrast. Surface containers use a slightly lighter charcoal (#1E1E1E) to create subtle hierarchy without reducing clarity.
- **Functional Accents:** Electric Blue is reserved strictly for connectivity status (RFID, Bluetooth, Cloud Sync). Bright Green and Red are used exclusively for Start and Stop actions to prevent accidental triggers through color-coded recognition.

## Typography

Readability is the primary metric for this design system. 

- **Data Visibility:** **Space Grotesk** is used for all numerical data, timers, and tag counts. Its geometric, monospaced-adjacent proportions ensure that numbers don't jump horizontally as they change, which is vital for live timers.
- **Instructional Clarity:** **Inter** handles all UI labels and body text. Its high x-height ensures legibility even when the device is vibrating or moving.
- **Hierarchy:** We use heavy weights (Bold/ExtraBold) as the default to ensure characters remain distinct against the dark background. All labels for data points should be in uppercase to maximize formal structure and "glanceability."

## Layout & Spacing

The layout follows a **fluid grid** model optimized for the narrow aspect ratio of rugged handhelds. 

- **Gloved-Ready Targets:** A strict minimum touch target of 56px is enforced for all interactive elements to accommodate gloved use and high-intensity environments.
- **Rhythm:** An 8px base unit drives all spacing decisions. Large outer margins (24px) prevent UI elements from being obscured by the physical ruggedized bezels or "lips" of the Chainway C72 casing.
- **Vertical Density:** Data is presented in a clear vertical stack. Cards are used to group related metrics (e.g., bib number, timestamp, and rank) with generous 24px gaps to prevent visual clutter.

## Elevation & Depth

In high-glare environments, shadows are often invisible. Therefore, this design system relies on **Tonal Layers** and **High-Contrast Outlines** rather than ambient shadows.

- **Surface Tiers:** Level 0 is the true black background. Level 1 is a dark charcoal card. Level 2 (for active or selected states) uses a bold 2px Safety Yellow border.
- **Direct Feedback:** When an element is pressed, it does not "sink" into the screen; instead, it should invert its colors (e.g., a Safety Yellow button turns black with yellow text). This provides an unmistakable visual confirmation of the interaction.
- **Depth through Saturation:** More critical information uses more saturated colors, while secondary background information is rendered in desaturated grays.

## Shapes

The shape language reflects the physical "rugged" nature of the hardware. 

- **Roundedness:** A setting of `2` (0.5rem / 8px) is applied to buttons and cards. This provides enough of a curve to feel modern and touchable with gloves, while maintaining a sturdy, rectangular footprint that maximizes screen real estate.
- **Functional Geometry:** Use hard angles for purely decorative or structural dividers, but keep consistent rounding on anything interactive.
- **Icons:** Use thick-stroke (2px minimum) iconography with rounded terminals to match the font and container styling.

## Components

- **Action Buttons:** Must span the full width of their container. The "Start" button should be bright green, and "Stop/Finish" should be bright red. Both must use a minimum height of 64px for emergency access.
- **Data Cards:** Cards use a #1E1E1E background. The primary data point (e.g., Bib Number) should be the largest element, placed in the top left, followed by the timestamp in the Space Grotesk font.
- **Status Chips:** Small, high-contrast pills located at the top of the screen. Use Electric Blue for "RFID ACTIVE" and a pulsing Red for "RECORDING."
- **Input Fields:** Ruggedized devices often use physical triggers for scanning; however, on-screen inputs must have a 2px border in a mid-gray, turning Safety Yellow when focused.
- **Progress Bars:** High-thickness (12px+) bars in Safety Yellow to indicate sync progress or battery levels, ensuring visibility even with a quick glance.