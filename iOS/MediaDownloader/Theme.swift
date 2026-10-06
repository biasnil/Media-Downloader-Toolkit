import SwiftUI
import UIKit

// Same palette as theme.py on desktop and the Android app
extension Color {
    static let ytRed = Color(red: 1, green: 0, blue: 0)
    static let appBackground = dynamic(light: 0xFFFFFF, dark: 0x0F0F0F)
    static let surface = dynamic(light: 0xF2F2F2, dark: 0x272727)
    static let outline = dynamic(light: 0xE0E0E0, dark: 0x3F3F3F)
    static let muted = dynamic(light: 0x606060, dark: 0xAAAAAA)

    /// A color that switches automatically between light and dark mode.
    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        let lightColor = uiColor(light)
        let darkColor = uiColor(dark)
        return Color(UIColor { $0.userInterfaceStyle == .dark ? darkColor : lightColor })
    }

    private static func uiColor(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
