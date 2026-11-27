import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c

    @State private var period: WidgetPeriod = .week

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        if h < 12 { return "Доброе утро" }
        if h < 18 { return "Добрый день" }
        return "Добрый вечер"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                    .font(.subheadline)
                    .foregroundStyle(c.muted)
                Text(greeting)
                    .font(.largeTitle.bold())
                    .foregroundStyle(c.ink)

                NavigationLink {
                    ProblemsView()
                } label: {
                    SurfaceCard {
                        let open = fleet.anomalies.filter { $0.status == "new" }
                        Text(open.isEmpty ? "Всё в порядке" : "\(open.count) проблем ждут решения")
                            .font(.headline)
                            .foregroundStyle(open.isEmpty ? c.ok : c.high)
                        Text(settings.demoMode ? "Демо-данные" : "По данным стенда")
                            .font(.caption)
                            .foregroundStyle(c.muted)
                    }
                }

                fleetTiles

                SectionLabel(text: "Виджеты")
                HStack {
                    ForEach(WidgetPeriod.allCases) { p in
                        PChip(title: p.title, selected: period == p) {
                            period = p
                            Task { await fleet.loadWidgetStats(period: p) }
                        }
                    }
                }

                WidgetSummaryCard(
                    title: "Простой с мотором",
                    period: period,
                    unit: "ч",
                    items: fleet.widgetItems,
                    metric: \.idle
                )
                WidgetSummaryCard(
                    title: "Расход топлива",
                    period: period,
                    unit: "л",
                    items: fleet.widgetItems,
                    metric: \.fuel
                )

                NavigationLink {
                    ReportView()
                } label: {
                    SurfaceCard {
                        Text("Доклад на \(settings.reportTime)")
                            .font(.headline)
                            .foregroundStyle(c.ink)
                        Text("Открыть сводку за сутки")
                            .font(.caption)
                            .foregroundStyle(c.muted)
                    }
                }

                if let err = fleet.lastError {
                    Text(err).font(.caption).foregroundStyle(c.med)
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationBarHidden(true)
        .task {
            await fleet.loadVehicles()
            await fleet.loadAnomalies()
            await fleet.loadWidgetStats(period: period)
        }
    }

    private var fleetTiles: some View {
        let total = max(fleet.vehicles.count, 1)
        return HStack(spacing: 10) {
            tile("\(fleet.vehicles.count)", "В парке")
            tile("\(max(total / 3, 1))", "На линии")
            tile("\(fleet.anomalies.filter { $0.status == "new" }.count)", "Проблемы")
        }
    }

    private func tile(_ value: String, _ label: String) -> some View {
        SurfaceCard(padding: 12) {
            Text(value).font(.title.bold()).foregroundStyle(c.ink)
            Text(label).font(.caption).foregroundStyle(c.muted)
        }
    }
}
