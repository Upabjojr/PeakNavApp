package com.peaknav.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void serbianInLatinIsRecognised() {
        assertTrue(I18NWrapper.isSerbianLatin(Locale.forLanguageTag("sr-Latn-RS")), "sr-Latn-RS");
        assertTrue(I18NWrapper.isSerbianLatin(Locale.forLanguageTag("sr-Latn")), "sr-Latn");
        assertFalse(I18NWrapper.isSerbianLatin(Locale.forLanguageTag("sr-RS")), "Serbian, Cyrillic by default");
        assertFalse(I18NWrapper.isSerbianLatin(new Locale("sr")), "the language chosen in the options");
        assertFalse(I18NWrapper.isSerbianLatin(Locale.forLanguageTag("hr-Latn")), "not Serbian");
    }

    @Test
    void serbianIsTransliterated() {
        assertEquals("Preuzimanje podataka", I18NWrapper.toSerbianLatin("Преузимање података"));
        assertEquals("Ljubljana, Džep, Đurđevac, Ćuprija", I18NWrapper.toSerbianLatin("Љубљана, Џеп, Ђурђевац, Ћуприја"));
        assertEquals("LJUBLJANA NJEGOŠ", I18NWrapper.toSerbianLatin("ЉУБЉАНА ЊЕГОШ"));
        assertEquals("Lj", I18NWrapper.toSerbianLatin("Љ"));
        assertEquals("%1$s km – OK", I18NWrapper.toSerbianLatin("%1$s km – OK"));
    }

    @Test
    void otherLanguagesAreUnchanged() {
        assertEquals(Locale.ITALY, I18NWrapper.translationLocale(Locale.ITALY));
        assertEquals(Locale.GERMAN, I18NWrapper.translationLocale(Locale.GERMAN));
        assertEquals(new Locale("pt", "BR"), I18NWrapper.translationLocale(new Locale("pt", "BR")));
    }
}
