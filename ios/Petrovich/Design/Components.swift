import SwiftUI

struct SurfaceCard<Content: View>: View {
    @Environment(\.petrovichColors) private var c
    var padding: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(c.surface)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

struct SectionLabel: View {
    @Environment(\.petrovichColors) private var c
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .tracking(0.7)
            .foregroundStyle(c.muted)
            .padding(.top, 6)
    }
}

struct PrimaryButton: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .foregroundStyle(c.onAccent)
                .background(enabled ? c.accent : c.faint)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .disabled(!enabled)
    }
}

struct SoftButton: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    var enabled: Bool = true
    var fill: Color? = nil
    var ink: Color? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .foregroundStyle(ink ?? c.ink)
                .background(fill ?? c.surface)
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(c.line, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .disabled(!enabled)
    }
}

struct PChip: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? c.bg : c.ink)
                .background(selected ? c.ink : c.surface)
                .overlay(
                    Capsule().stroke(selected ? c.ink : c.line, lineWidth: 1)
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct SettingsSwitchRow: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(c.ink)
                Text(subtitle).font(.caption).foregroundStyle(c.muted)
            }
        }
        .tint(c.accent)
        .padding(.vertical, 8)
    }
}
