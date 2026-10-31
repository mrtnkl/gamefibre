package com.gamefibre.stores

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GogBuildUrlsTest {
    @Test fun versionTwoBuildsProvideMultipleEndpointsWithoutLink() {
        val build = JSONObject("""{"generation":2,"urls":[
            {"url":"https://gog-cdn.gcdn.co/content-system/v2/meta/ab/cd/abcdef"},
            {"url":"https://gog-cdn-fastly.gog.com/content-system/v2/meta/ab/cd/abcdef"}
        ]}""")
        assertEquals(listOf(
            "https://gog-cdn.gcdn.co/content-system/v2/meta/ab/cd/abcdef",
            "https://gog-cdn-fastly.gog.com/content-system/v2/meta/ab/cd/abcdef"
        ), gogBuildUrls(build))
    }

    @Test fun legacyGenerationOneBuildStillUsesLink() {
        val url = "https://cdn.gog.com/content-system/v1/manifests/123/windows/456/repository.json"
        assertEquals(listOf(url), gogBuildUrls(JSONObject().put("generation", 1).put("link", url)))
    }

    @Test fun generationOneAlsoSupportsVersionTwoEndpointListings() {
        val url = "https://gog-cdn.gcdn.co/content-system/v1/manifests/123/windows/456/repository.json"
        val build = JSONObject("""{"generation":1,"urls":[{"url":"$url"}]}""")
        assertEquals(listOf(url), gogBuildUrls(build))
    }

    @Test fun expandsEndpointParametersAndIgnoresMissingAddresses() {
        val build = JSONObject("""{"link":null,"urls":[{"url":null},{"url":""},
            {"url_format":"https://{host}/content-system/{path}",
             "parameters":{"host":"cdn.gog.com","path":"v2/meta/ab/cd/abcdef"}}
        ]}""")
        assertEquals(listOf("https://cdn.gog.com/content-system/v2/meta/ab/cd/abcdef"), gogBuildUrls(build))
    }

    @Test(expected = IllegalStateException::class)
    fun missingAddressesGiveActionableFailure() {
        gogBuildUrls(JSONObject("""{"urls":[{"url_format":"https://{missing}/manifest"}]}"""))
    }
}
