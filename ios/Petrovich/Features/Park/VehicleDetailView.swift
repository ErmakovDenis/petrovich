import SwiftUI

struct VehicleDetailView: View {
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c
    let vehicle: Vehicle

    private var related: [Anomaly] {
        fleet.anomalies.filter { $0.vehicleId == vehicle.id }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SurfaceCard {
                    Text(vehicle.name).font(.title2.bold()).foregroundStyle(c.ink)
                    if let plate = vehicle.plate {
                        Text(plate).font(.headline).foregroundStyle(c.accent)
                    }
                    if let group = vehicle.group {
                        Text(group).font(.caption).foregroundStyle(c.muted)
                    }
                }

                SectionLabel(text: "Показатели")
                SurfaceCard {
                    Text("Карточка волны 2: подробная телеметрия и плитки расхода/простоя подключаются в волне 3–6.")
                        .font(.footnote)
                        .foregroundStyle(c.muted)
                }

                if !related.isEmpty {
                    SectionLabel(text: "Проблемы")
                    ForEach(related) { a in
                        NavigationLink {
                            AnomalyDetailView(anomaly: a)
                        } label: {
                            SurfaceCard {
                                Text(a.title).font(.subheadline.weight(.semibold)).foregroundStyle(c.ink)
                                Text(a.severity.uppercased()).font(.caption2).foregroundStyle(severityColor(a.severity))
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
        }
        .background(c.bg.ignoresSafeArea())
        .navigationTitle(vehicle.plate ?? vehicle.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await fleet.loadAnomalies() }
    }

    private func severityColor(_ s: String) -> Color {
        switch s {
        case "high": return c.high
        case "med": return c.med
        default: return c.muted
        }
    }
}
