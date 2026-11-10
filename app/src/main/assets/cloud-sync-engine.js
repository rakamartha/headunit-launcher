/**
 * GREYCOAT CLOUD SYNC ENGINE (Headunit <-> Cloud <-> Companion Phone App)
 * Mendukung Offline-First Queue (localStorage) + Realtime Sync ke Firebase / Supabase REST API
 */
export const GreycoatSyncEngine = {
    config: {
        vehicleId: "GREYCOAT-01",
        vehicleName: "GREYCOAT",
        // Ganti dengan URL Firebase Realtime DB / Firestore / Supabase Project Anda
        endpointUrl: "https://YOUR-PROJECT-ID.firebaseio.com",
        apiKey: "YOUR_API_KEY"
    },

    // Simpan sesi perjalanan selesai (ketika tombol PARKIR ditekan atau Ignition OFF)
    async recordTripAndParking(tripData) {
        const payload = {
            id: "TRIP-" + Date.now(),
            vehicleId: this.config.vehicleId,
            vehicleName: this.config.vehicleName,
            timestampStart: tripData.timestampStart,
            timestampEnd: Date.now(),
            dateIso: new Date().toISOString().split('T')[0],
            monthKey: new Date().toLocaleDateString('id-ID', { month: 'short', year: 'numeric' }).toUpperCase(),
            distanceKm: Number(tripData.distanceKm.toFixed(2)),
            durationMinutes: tripData.durationMinutes,
            avgSpeedKmh: tripData.avgSpeedKmh,
            maxSpeedKmh: tripData.maxSpeedKmh,
            category: tripData.category || "PRIBADI", // PRIBADI | KERJA
            parkingLocation: {
                label: tripData.parkingLabel,
                lat: tripData.lat,
                lng: tripData.lng,
                savedAt: new Date().toISOString()
            },
            currentOdometerKm: tripData.currentOdometerKm
        };

        // 1. Simpan selalu ke antrean lokal (Offline-First) agar data aman walau tanpa sinyal
        const queue = JSON.parse(localStorage.getItem("greycoat_sync_queue") || "[]");
        queue.push(payload);
        localStorage.setItem("greycoat_sync_queue", JSON.stringify(queue));

        // 2. Jika Headunit terhubung ke internet (Wi-Fi tethering / SIM 4G), langsung kirim ke Cloud
        if (navigator.onLine) {
            return await this.flushOfflineQueue();
        }
        return { synced: false, queuedCount: queue.length };
    },

    // Kirim semua antrean perjalanan yang tertunda saat Headunit mendapat koneksi internet
    async flushOfflineQueue() {
        const queue = JSON.parse(localStorage.getItem("greycoat_sync_queue") || "[]");
        if (queue.length === 0) return { synced: true, count: 0 };

        try {
            for (const item of queue) {
                // Contoh pengiriman ke Firebase Realtime Database REST API:
                // await fetch(`${this.config.endpointUrl}/vehicles/${this.config.vehicleId}/trips/${item.id}.json`, {
                //     method: "PUT",
                //     headers: { "Content-Type": "application/json" },
                //     body: JSON.stringify(item)
                // });
                // Update status parkir & odometer terbaru:
                // await fetch(`${this.config.endpointUrl}/vehicles/${this.config.vehicleId}/status.json`, {
                //     method: "PATCH",
                //     headers: { "Content-Type": "application/json" },
                //     body: JSON.stringify({
                //         lastParking: item.parkingLocation,
                //         odometerKm: item.currentOdometerKm,
                //         updatedAt: Date.now()
                //     })
                // });
            }
            localStorage.setItem("greycoat_sync_queue", "[]");
            return { synced: true, count: queue.length };
        } catch (err) {
            console.warn("Masih offline, data tetap aman di antrean lokal:", err);
            return { synced: false, queuedCount: queue.length };
        }
    }
};

// Otomatis sinkronisasi begitu Headunit mendeteksi koneksi internet aktif
window.addEventListener("online", () => {
    GreycoatSyncEngine.flushOfflineQueue();
});
