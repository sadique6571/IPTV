package com.sadique.iptv

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeMatchTest {
    @Test fun readsEpisodeNumbersAndCountOnlySeasonRecords() {
        val data = JSONObject("""{"seasons":[{"se":1,"episodes":[1,3]},{"se":2,"maxEp":2}]}""")
        val seasons = parseSeasons(data)
        org.junit.Assert.assertEquals(listOf(1, 3), seasons[0].episodes.map { it.number })
        org.junit.Assert.assertEquals(listOf(1, 2), seasons[1].episodes.map { it.number })
    }

    @Test fun joinsOnlyTheExactEpisodeResource() {
        val row = JSONObject().put("se", 1).put("ep", 2).put("resourceId", "123")
        val detector = JSONObject().put("resourceId", "123")
        assertTrue(sameEpisodeFile(detector, row, 1, 2))
        assertFalse(sameEpisodeFile(detector, row, 1, 3))
        assertFalse(sameEpisodeFile(detector.put("ep", 1), row, 1, 2))
        assertFalse(sameEpisodeFile(JSONObject().put("resourceId", "456"), row, 1, 2))
    }

    @Test fun inheritsMissingEpisodeFieldsFromParent() {
        val parent = JSONObject().put("season", 2).put("episode", 3)
        assertTrue(matchesEpisode(JSONObject(), parent, 2, 3))
        assertFalse(matchesEpisode(JSONObject().put("ep", 4), parent, 2, 3))
        assertFalse(matchesEpisode(JSONObject(), null, 2, 3))
    }
}
