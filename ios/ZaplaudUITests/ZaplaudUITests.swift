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

    func testFixtureLoginConversationSendAndDraftRecovery() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        let origin = try XCTUnwrap(environment["ZAPLAUD_FIXTURE_URL"])
        XCTAssertTrue(origin.hasPrefix("http://127.0.0.1:"))
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(pt-BR)"]
        app.launch()
        let server = element(app, "server-url")
        server.tap()
        server.typeText(origin)
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
        XCUIDevice.shared.press(.home)
        sleep(30)
        app.activate()
        XCTAssertTrue(element(app, "message-input").value as? String == "ios-smoke-unsent-draft")
        app.terminate()
        app.launch()
        XCTAssertTrue(element(app, "message-input").value as? String == "ios-smoke-unsent-draft")
        capture("draft-restored")
        XCUIDevice.shared.orientation = .portrait
    }
}
