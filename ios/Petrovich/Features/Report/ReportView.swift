import SwiftUI

struct ReportView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c

    var body: some View {
        let idle = WidgetStatsMath.total(items: fleet.widgetItems, period: .day) { $0.idle }
        let fuel = WidgetStatsMath.total(items: fleet.widgetItems, period: .day) { $0.fuel }
        let open = fleet.anomalies.filter { $0.status == "new" }

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Доклад на \(settings.reportTime)")
                    .font(.largeTitle.bold())
                    .foregroundStyle(c.ink)
                Text("Краткая сводка за последние сутки (упрощённый экран волны 4).")
                    .font(.subheadline)
                    .foregroundStyle(c.muted)

                SurfaceCard {
                    metric("Простой с мотором", Formatters.hours(idle.value))
                    Divider()
                    metric("Расход топлива", Formatters.liters(fuel.value))
                    Divider()
                    metric("Новые проблемы", "\(open.count)")
                }

                if !open.isEmpty {
                    SectionLabel(text: "На что обратить внимание")
                    ForEach(open.prefix(5)) { a in
                        SurfaceCard {
                            Text(a.title).font(.subheadline.weight(.semibold))
                            Text(a.vehicleName).font(.caption).foregroundStyle(c.muted)
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Доклад")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await fleet.loadWidgetStats(period: .week)
            await fleet.loadAnomalies()
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(c.muted)
            Spacer()
            Text(value).font(.headline).foregroundStyle(c.ink)
        }
        .padding(.vertical, 6)
    }
}
