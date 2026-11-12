package data.localimport

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterRecognitionTest {

    @Test
    fun knownNumberReturnedAsIs() {
        assertEquals(12.0, ChapterRecognition.parseChapterNumber("Bleach", "Bleach 999", 12.0))
        assertEquals(-2.0, ChapterRecognition.parseChapterNumber("Bleach", "Bleach 1", -2.0))
    }

    @Test
    fun singleNumber() {
        assertEquals(567.0, ChapterRecognition.parseChapterNumber("Bleach", "Bleach 567: Down With Snowwhite"))
        assertEquals(12.0, ChapterRecognition.parseChapterNumber("One Piece", "Chapter 12: Adventure"))
    }

    @Test
    fun baseCaseChDot() {
        assertEquals(
            4.0,
            ChapterRecognition.parseChapterNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation"),
        )
    }

    @Test
    fun unwantedVolumeTagsStripped() {
        assertEquals(
            12.0,
            ChapterRecognition.parseChapterNumber("Prison School", "Prison School 12 v.1 vol004 version1243 volume64"),
        )
    }

    @Test
    fun specialSuffixes() {
        assertEquals(12.99, ChapterRecognition.parseChapterNumber("One Piece", "One Piece 12 extra"))
        assertEquals(12.98, ChapterRecognition.parseChapterNumber("One Piece", "One Piece 12 omake"))
        assertEquals(12.97, ChapterRecognition.parseChapterNumber("One Piece", "One Piece 12 special"))
    }

    @Test
    fun alphaPostfix() {
        assertEquals(12.1, ChapterRecognition.parseChapterNumber("Naruto", "Naruto 12a"))
        assertEquals(12.2, ChapterRecognition.parseChapterNumber("Naruto", "Naruto 12.b"))
    }

    @Test
    fun decimalNumber() {
        assertEquals(12.5, ChapterRecognition.parseChapterNumber("Naruto", "Naruto 12.5"))
    }

    @Test
    fun unknownWhenNoNumber() {
        assertEquals(
            ChapterRecognition.UNKNOWN_CHAPTER_NUMBER,
            ChapterRecognition.parseChapterNumber("Manga", "Prologue"),
        )
    }
}
