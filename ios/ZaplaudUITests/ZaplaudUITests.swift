import XCTest

final class ZaplaudUITests: XCTestCase {
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        let value = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
        XCTAssertTrue(value.waitForExistence(timeout: 30), "Missing accessible control: \(identifier)")
        return value
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private struct FixtureStats: Decodable {
        let sse: Int
    }

    private func expectStreamCount(_ expected: Int, statsURL: URL) throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 10
        var observed: Int
        repeat {
            let data = try Data(contentsOf: statsURL)
            observed = try JSONDecoder().decode(FixtureStats.self, from: data).sse
            if observed == expected { return }
            Thread.sleep(forTimeInterval: 0.25)
        } while ProcessInfo.processInfo.systemUptime < deadline
        XCTAssertEqual(observed, expected, "Unexpected number of /api/stream connections")
    }

    func testFixtureLoginConversationSendAndDraftRecovery() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        let origin = try XCTUnwrap(environment["ZAPLAUD_FIXTURE_URL"])
        XCTAssertTrue(origin.hasPrefix("http://127.0.0.1:"))
        let localOrigin = try XCTUnwrap(environment["ZAPLAUD_FIXTURE_LOCAL_URL"])
        var stats = try XCTUnwrap(URLComponents(string: localOrigin))
        // The UI test runner doesn't inherit the app's App Transport Security exceptions.
        stats.host = "localhost"
        stats.path = "/api/fixture/stats"
        let statsURL = try XCTUnwrap(stats.url)
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(pt-BR)"]
        app.launch()
        let server = element(app, "server-url")
        server.tap()
        server.typeText(origin)
        XCUIDevice.shared.press(.home)
        let loginInBackground = NSPredicate { _, _ in
            app.state == .runningBackground || app.state == .runningBackgroundSuspended
        }
        let backgrounded = XCTNSPredicateExpectation(predicate: loginInBackground, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [backgrounded], timeout: 10), .completed)
        app.activate()
        XCTAssertEqual(element(app, "server-url").value as? String, origin)
        let username = element(app, "username")
        username.tap()
        username.typeText("android-test")
        let password = element(app, "password")
        password.tap()
        password.typeText("android-test")
        element(app, "login-submit").tap()
        element(app, "http-confirm").tap()
        element(app, "session-row:claude:00000000-0000-4000-8000-000000000001").tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Resposta fictícia")).firstMatch.waitForExistence(timeout: 30))
        capture("conversation-portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        capture("conversation-landscape")
        let input = element(app, "message-input")
        input.tap()
        input.typeText("ios-smoke-message")
        element(app, "message-send").tap()
        XCTAssertTrue(element(app, "message-status-queued").waitForExistence(timeout: 30))
        let composerReady = NSPredicate(format: "enabled == true AND value == %@", "")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: composerReady, object: input)], timeout: 30), .completed)
        // A later draft must survive backgrounding and process death without replaying the POST.
        input.tap()
        input.typeText("ios-smoke-unsent-draft")
        let draftSaved = NSPredicate(format: "value == %@", "ios-smoke-unsent-draft")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: draftSaved, object: input)], timeout: 10), .completed)
        let attach = app.buttons.matching(NSPredicate(format: "label == %@", "Anexar arquivos (até 5)")).firstMatch
        XCTAssertTrue(attach.waitForExistence(timeout: 10))
        attach.tap()
        let pickerDismissRegion = app.descendants(matching: .any).matching(identifier: "PopoverDismissRegion").firstMatch
        XCTAssertTrue(pickerDismissRegion.waitForExistence(timeout: 10), "Missing iPad document picker")
        // Tap outside the centered document picker, within UIKit's dismiss region.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.1)).tap()
        XCTAssertTrue(pickerDismissRegion.waitForNonExistence(timeout: 10), "Document picker did not dismiss")
        XCTAssertEqual(element(app, "message-input").value as? String, "ios-smoke-unsent-draft")
        try expectStreamCount(1, statsURL: statsURL)
        XCUIDevice.shared.press(.home)
        try expectStreamCount(0, statsURL: statsURL)
        sleep(30)
        app.activate()
        try expectStreamCount(1, statsURL: statsURL)
        XCTAssertTrue(element(app, "message-input").value as? String == "ios-smoke-unsent-draft")
        app.terminate()
        app.launch()
        XCTAssertTrue(element(app, "message-input").value as? String == "ios-smoke-unsent-draft")
        capture("draft-restored")
        XCUIDevice.shared.orientation = .portrait
    }
}
