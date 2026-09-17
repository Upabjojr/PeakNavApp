package com.peaknav.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

public class TestTutorialImages {

    @Test
    void readsEveryPictureOnceInSlideOrder() {
        String html = "const SLIDES = [\n"
                + "  { \"image\": \"a.jpg\", \"key\": \"K\" },\n"
                + "  {\"image\":\"b.jpg\", \"marker\": null},\n"
                + "  { \"image\": \"a.jpg\" }\n];";
        assertEquals(Arrays.asList("a.jpg", "b.jpg"), TutorialImages.namesIn(html));
        assertEquals(0, TutorialImages.namesIn("no slides here").size());
    }

}
