package com.sadique.iptv

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeMatchTest {
    @Test fun inheritsMissingEpisodeFieldsFromParent() {
        val parent = JSONObject().put("season", 2).put("episode", 3)
        assertTrue(matchesEpisode(JSONObject(), parent, 2, 3))
        assertFalse(matchesEpisode(JSONObject().put("ep", 4), parent, 2, 3))
        assertFalse(matchesEpisode(JSONObject(), null, 2, 3))
    }
}
