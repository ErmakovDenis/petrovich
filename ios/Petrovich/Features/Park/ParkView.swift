import SwiftUI

struct ParkView: View {
    @EnvironmentObject private var fleet: FleetRepository
    @Environment(\.petrovichColors) private var c
    @State private var query = ""

    private var filtered: [Vehicle] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return fleet.vehicles }
        return fleet.vehicles.filter {
            $0.name.lowercased().contains(q) || ($0.plate?.lowercased().contains(q) ?? false)
        }
    }

    var body: some View {
        List {
            ForEach(filtered) { vehicle in
                NavigationLink {
                    VehicleDetailView(vehicle: vehicle)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(vehicle.listName)
                            .font(.headline)
                            .foregroundStyle(c.ink)
                        if let group = vehicle.group {
                            Text(group).font(.caption).foregroundStyle(c.muted)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(c.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(c.bg.ignoresSafeArea())
        .navigationTitle("Парк")
        .searchable(text: $query, prompt: "Номер или имя")
        .refreshable { await fleet.loadVehicles() }
        .task { await fleet.loadVehicles() }
        .overlay {
            if fleet.vehicles.isEmpty {
                ContentUnavailableView(
                    "Нет машин",
                    systemImage: "truck.box",
                    description: Text(fleet.lastError ?? "Проверьте подключение")
                )
            }
        }
    }
}
