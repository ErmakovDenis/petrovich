import SwiftUI

/// Палитра как в Android `PetrovichColors` (Theme.kt).
struct PetrovichColors {
    let bg: Color
    let surface: Color
    let surface2: Color
    let line: Color
    let ink: Color
    let muted: Color
    let faint: Color
    let accent: Color
    let onAccent: Color
    let accentSoft: Color
    let high: Color
    let highSoft: Color
    let med: Color
    let medSoft: Color
    let ok: Color
    let okSoft: Color
    let isDark: Bool

    static let light = PetrovichColors(
        bg: Color(hex: 0xF4F5F7),
        surface: Color(hex: 0xFFFFFF),
        surface2: Color(hex: 0xEBEEF1),
        line: Color(hex: 0xE0E4E8),
        ink: Color(hex: 0x1B1F24),
        muted: Color(hex: 0x5B616B),
        faint: Color(hex: 0x8A9099),
        accent: Color(hex: 0x1D3F66),
        onAccent: Color(hex: 0xFFFFFF),
        accentSoft: Color(hex: 0xE3EAF3),
        high: Color(hex: 0xB42318),
        highSoft: Color(hex: 0xFDECEA),
        med: Color(hex: 0x8A4B00),
        medSoft: Color(hex: 0xFFF4D6),
        ok: Color(hex: 0x067647),
        okSoft: Color(hex: 0xE6F4EC),
        isDark: false
    )

    static let dark = PetrovichColors(
        bg: Color(hex: 0x161A1E),
        surface: Color(hex: 0x1F252B),
        surface2: Color(hex: 0x272E35),
        line: Color(hex: 0x363E46),
        ink: Color(hex: 0xEDEEF0),
        muted: Color(hex: 0x9BA3AD),
        faint: Color(hex: 0x6C7580),
        accent: Color(hex: 0x6E9BD6),
        onAccent: Color(hex: 0x0B1520),
        accentSoft: Color(hex: 0x223246),
        high: Color(hex: 0xFF8A75),
        highSoft: Color(hex: 0x3A1D18),
        med: Color(hex: 0xF0B429),
        medSoft: Color(hex: 0x3A2C10),
        ok: Color(hex: 0x4CC38A),
        okSoft: Color(hex: 0x14301F),
        isDark: true
    )
}

private struct PetrovichColorsKey: EnvironmentKey {
    static let defaultValue = PetrovichColors.light
}

extension EnvironmentValues {
    var petrovichColors: PetrovichColors {
        get { self[PetrovichColorsKey.self] }
        set { self[PetrovichColorsKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String {
        switch self {
        case .system: return "Системная"
        case .light: return "Светлая"
        case .dark: return "Тёмная"
        }
    }
}
