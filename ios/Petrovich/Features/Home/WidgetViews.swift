import SwiftUI

struct WidgetSummaryCard: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    let period: WidgetPeriod
    let unit: String
    let items: [DailyVehicleStats]
    let metric: KeyPath<DailyVehicleStats, Double>

    var body: some View {
        let result = WidgetStatsMath.total(items: items, period: period) { $0[keyPath: metric] }
        NavigationLink {
            WidgetDetailView(title: title, period: period, unit: unit, items: items, metric: metric)
        } label: {
            SurfaceCard {
                HStack {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(c.ink)
                    Spacer()
                    Text(period.title.lowercased()).font(.caption).foregroundStyle(c.muted)
                }
                Text(format(result.value))
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(c.ink)
                MiniBarChart(points: result.points)
                    .frame(height: 56)
            }
        }
        .buttonStyle(.plain)
    }

    private func format(_ v: Double) -> String {
        unit == "ч" ? Formatters.hours(v) : Formatters.liters(v)
    }
}

struct WidgetDetailView: View {
    @Environment(\.petrovichColors) private var c
    let title: String
    @State var period: WidgetPeriod
    let unit: String
    let items: [DailyVehicleStats]
    let metric: KeyPath<DailyVehicleStats, Double>
    @State private var selected = 0

    var body: some View {
        let result = WidgetStatsMath.total(items: items, period: period) { $0[keyPath: metric] }
        let points = result.points
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    ForEach(WidgetPeriod.allCases) { p in
                        PChip(title: p.title, selected: period == p) { period = p; selected = 0 }
                    }
                }
                SurfaceCard {
                    Text(title).font(.headline)
                    Text(period.title).font(.caption).foregroundStyle(c.muted)
                    Text(format(result.value))
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                    MiniBarChart(points: points, selected: $selected)
                        .frame(height: 100)
                    if points.indices.contains(selected) {
                        let p = points[selected]
                        Text("\(p.label.replacingOccurrences(of: "\n", with: "–")) · \(format(p.value))")
                            .font(.subheadline.weight(.semibold))
                    }
                    if period.bucketDays >= 61 || points.count >= 10 {
                        ChartAxisLabels(points: points, selected: selected)
                    } else {
                        ChartAxisLabels(points: points, selected: selected)
                    }
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func format(_ v: Double) -> String {
        unit == "ч" ? Formatters.hours(v) : Formatters.liters(v)
    }
}

struct MiniBarChart: View {
    @Environment(\.petrovichColors) private var c
    let points: [WidgetPoint]
    var selected: Binding<Int>? = nil

    var body: some View {
        let maxV = max(points.map(\.value).max() ?? 1, 0.01)
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(Array(points.enumerated()), id: \.offset) { i, p in
                let on = selected?.wrappedValue == i
                RoundedRectangle(cornerRadius: 4)
                    .fill(on ? c.accent : c.accentSoft)
                    .frame(maxWidth: .infinity)
                    .frame(height: max(8, 100 * (p.value / maxV)))
                    .onTapGesture { selected?.wrappedValue = i }
            }
        }
    }
}

struct ChartAxisLabels: View {
    @Environment(\.petrovichColors) private var c
    let points: [WidgetPoint]
    let selected: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(points.enumerated()), id: \.offset) { i, p in
                Text(p.label)
                    .font(.system(size: points.count >= 10 ? 9 : 11))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .foregroundStyle(i == selected ? c.ink : c.muted)
                    .fontWeight(i == selected ? .semibold : .regular)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}
