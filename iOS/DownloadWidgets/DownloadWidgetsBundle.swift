import SwiftUI
import WidgetKit

/// Everything in the DownloadWidgets extension. Only the Live Activity for now.
@main
struct DownloadWidgetsBundle: WidgetBundle {
    var body: some Widget {
        DownloadLiveActivity()
    }
}
