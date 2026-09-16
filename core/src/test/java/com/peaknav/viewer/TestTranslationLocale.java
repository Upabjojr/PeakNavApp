package com.peaknav.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

public class TestTranslationLocale {

    @Test
    void norwegianReadsTheNoTranslation() {
        assertEquals("no", I18NWrapper.translationLocale(new Locale("nb", "NO")).getLanguage(), "Bokmal, as iOS and Android report it");
        assertEquals("NO", I18NWrapper.translationLocale(new Locale("nb", "NO")).getCountry(), "the country is kept");
        assertEquals("no", I18NWrapper.translationLocale(new Locale("nn")).getLanguage(), "Nynorsk");
        assertEquals("no", I18NWrapper.translationLocale(new Locale("no")).getLanguage(), "already no");
    }

    @Test
    void otherLanguagesAreUnchanged() {
        assertEquals(Locale.ITALY, I18NWrapper.translationLocale(Locale.ITALY));
        assertEquals(Locale.GERMAN, I18NWrapper.translationLocale(Locale.GERMAN));
        assertEquals(new Locale("pt", "BR"), I18NWrapper.translationLocale(new Locale("pt", "BR")));
    }
}
