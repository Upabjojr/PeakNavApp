package com.peaknav.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Net;
import com.peaknav.ui.TextFieldsCallback;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The headless writer records what the app tried to ask, and asks no one. No GL needed. */
class SuppressedPromptsTest {

    @Test
    void promptsAreRecordedInOrderAndNeverAnswered() {
        FileSnapshotWriter writer = new FileSnapshotWriter();
        AtomicBoolean answeredYes = new AtomicBoolean();
        writer.promptYesNo("Title", "Point the camera?", () -> answeredYes.set(true));
        writer.alertMessage("line one\nline two");
        AtomicBoolean cancelled = new AtomicBoolean();
        writer.promptForTextFields("Token", null, new String[] {"a"}, null, new TextFieldsCallback() {
            @Override
            public void onEntered(String[] values) {
            }

            @Override
            public void onCancelled() {
                cancelled.set(true);
            }
        });
        writer.pickGpxFile();

        assertFalse(answeredYes.get(), "nobody said yes");
        assertTrue(cancelled.get(), "a text prompt is cancelled, so its caller does not wait");
        assertEquals(4, writer.suppressedPromptCount());
        List<FileSnapshotWriter.SuppressedPrompt> all = writer.suppressedPromptsAfter(0);
        assertEquals("yes_no", all.get(0).kind);
        assertEquals("Title - Point the camera?", all.get(0).detail);
        assertEquals("gpx_pick", all.get(3).kind);
        List<FileSnapshotWriter.SuppressedPrompt> later = writer.suppressedPromptsAfter(2);
        assertEquals(2, later.size());
        assertEquals(3, later.get(0).seq);
    }

    @Test
    void aChoiceTakesItsSecondAnswer() {
        FileSnapshotWriter writer = new FileSnapshotWriter();
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        writer.promptChoice("Where are you?", "No GPS", "Estimate", "Search",
                () -> first.set(true), () -> second.set(true));

        assertFalse(first.get(), "nobody chose the first");
        assertTrue(second.get(), "the second is answered, so its caller does not wait");
        assertEquals("choice", writer.suppressedPromptsAfter(0).get(0).kind);
        assertEquals("Where are you? - No GPS", writer.suppressedPromptsAfter(0).get(0).detail);
    }

    @Test
    void toastsAreMessagesNotPrompts() {
        FileSnapshotWriter writer = new FileSnapshotWriter();
        writer.makeToast("Download complete!");
        writer.makeToast("");
        writer.makeToast("Track loaded");

        assertEquals(0, writer.suppressedPromptCount(), "a toast asks nothing");
        assertEquals(2, writer.messageCount(), "an empty toast is not a message");
        List<FileSnapshotWriter.SuppressedPrompt> later = writer.messagesAfter(1);
        assertEquals(1, later.size());
        assertEquals("toast", later.get(0).kind);
        assertEquals("Track loaded", later.get(0).detail);
    }

    @Test
    void openUriIsRecordedInsteadOfLaunchingABrowser() {
        Net failing = (Net) Proxy.newProxyInstance(Net.class.getClassLoader(), new Class<?>[] {Net.class},
                (proxy, method, args) -> {
                    throw new AssertionError("reached the real Net: " + method.getName());
                });
        FileSnapshotWriter writer = new FileSnapshotWriter();
        assertFalse(writer.withoutBrowser(failing).openURI("https://peaknav.com"));
        assertEquals("browser", writer.suppressedPromptsAfter(0).get(0).kind);
        assertEquals("https://peaknav.com", writer.suppressedPromptsAfter(0).get(0).detail);
    }
}
