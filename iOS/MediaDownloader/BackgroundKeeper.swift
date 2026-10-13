import AVFoundation
import Foundation

/// Keeps the app running while the phone is locked, so downloads don't pause.
/// iOS lets apps that are playing audio keep running in the background, so while a
/// download is going this plays silence, mixed with your other audio (it won't stop music).
/// Needs "audio" in UIBackgroundModes (Info.plist). Settings → "Keep downloading when locked".
@MainActor
final class BackgroundKeeper {
    static let shared = BackgroundKeeper()

    private var player: AVAudioPlayer?
    private var users = 0
    private var observer: NSObjectProtocol?

    /// True while silence is playing (the app won't be paused).
    var isKeepingAlive: Bool { player?.isPlaying == true }

    private var enabled: Bool {
        UserDefaults.standard.object(forKey: "keepAlive") as? Bool ?? true
    }

    /// Call when a batch starts. Each begin() needs one end().
    func begin() {
        users += 1
        guard users == 1, enabled else { return }
        start()
    }

    func end() {
        users = max(0, users - 1)
        if users == 0 { stop() }
    }

    private func start() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, options: [.mixWithOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(data: Self.silence)
            player.numberOfLoops = -1
            player.volume = 0
            player.play()
            self.player = player
        } catch {
            player = nil
            return
        }

        // A phone call or alarm stops the audio; start it again afterwards
        observer = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw == AVAudioSession.InterruptionType.ended.rawValue else { return }
            MainActor.assumeIsolated {
                guard let self, self.users > 0 else { return }
                try? AVAudioSession.sharedInstance().setActive(true)
                self.player?.play()
            }
        }
    }

    private func stop() {
        player?.stop()
        player = nil
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// One second of silent 16-bit mono WAV, built in memory.
    private static let silence: Data = {
        let sampleRate = 8000
        let samples = sampleRate
        let dataSize = samples * 2
        var d = Data()
        func put(_ s: String) { d.append(contentsOf: Array(s.utf8)) }
        func put32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { d.append(contentsOf: $0) } }
        func put16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { d.append(contentsOf: $0) } }
        put("RIFF"); put32(36 + dataSize); put("WAVE")
        put("fmt "); put32(16); put16(1); put16(1); put32(sampleRate); put32(sampleRate * 2); put16(2); put16(16)
        put("data"); put32(dataSize)
        d.append(Data(count: dataSize))
        return d
    }()
}
