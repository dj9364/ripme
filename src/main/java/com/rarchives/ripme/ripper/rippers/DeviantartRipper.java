package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Base64;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

public class DeviantartRipper extends AbstractHTMLRipper {

    private static final int PAGE_SLEEP_TIME  = 3000,
                             IMAGE_SLEEP_TIME = 2000;

    private Map<String,String> cookies = new HashMap<>();
    private Set<String> triedURLs = new HashSet<>();

    public DeviantartRipper(URL url) throws IOException {
        super(url);
    }

    String loginCookies = "auth=__0f9158aaec09f417b235%3B%221ff79836392a515d154216d919eae573%22;" +
            "auth_secure=__41d14dd0da101f411bb0%3B%2281cf2cf9477776162a1172543aae85ce%22;" +
            "userinfo=__bf84ac233bfa8ae642e8%3B%7B%22username%22%3A%22grabpy%22%2C%22uniqueid%22%3A%22a0a876aa37dbd4b30e1c80406ee9c280%22%2C%22vd%22%3A%22BbHUXZ%2CBbHUXZ%2CA%2CU%2CA%2C%2CB%2CA%2CB%2CBbHUXZ%2CBbHUdj%2CL%2CL%2CA%2CBbHUdj%2C13%2CA%2CB%2CA%2C%2CA%2CA%2CB%2CA%2CA%2C%2CA%22%2C%22attr%22%3A56%7D";

    @Override
    public String getHost() {
        return "deviantart";
    }
    @Override
    public String getDomain() {
        return "deviantart.com";
    }
    @Override
    public boolean hasDescriptionSupport() {
        return true;
    }
    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException {
        String u = url.toExternalForm();

        if (u.replace("/", "").endsWith(".deviantart.com")) {
            // Root user page, get all albums
            if (!u.endsWith("/")) {
                u += "/";
            }
            u += "gallery/?";
        }

        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9\\-]+)\\.deviantart\\.com/favou?rites/([0-9]+)/*?$");
        Matcher m = p.matcher(url.toExternalForm());
        if (!m.matches()) {
            String subdir = "/";
            if (u.contains("catpath=scraps")) {
                subdir = "scraps";
            }
            u = u.replaceAll("\\?.*", "?catpath=" + subdir);
        }
        return new URL(u);
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9\\-]+)\\.deviantart\\.com(/gallery)?/?(\\?.*)?$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Root gallery
            if (url.toExternalForm().contains("catpath=scraps")) {
                return m.group(1) + "_scraps";
            }
            else {
                return m.group(1);
            }
        }
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]+)\\.deviantart\\.com/gallery/([0-9]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Subgallery
            return m.group(1) + "_" + m.group(2);
        }
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]+)\\.deviantart\\.com/favou?rites/([0-9]+)/.*?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1) + "_faves_" + m.group(2);
        }
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]+)\\.deviantart\\.com/favou?rites/?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Subgallery
            return m.group(1) + "_faves";
        }
        throw new MalformedURLException("Expected URL format: http://username.deviantart.com/[/gallery/#####], got: " + url);
    }

    /**
     * Gets first page.
     * Will determine if login is supplied,
     * if there is a login, then login and add that login cookies.
     * Otherwise, just bypass the age gate with an anonymous flag.
     * @return
     * @throws IOException 
     */
    @Override
    public Document getFirstPage() throws IOException {
        
        // Base64 da login
        // username: Z3JhYnB5
        // password: ZmFrZXJz


        cookies = getDACookies();
            if (cookies.isEmpty()) {
                LOGGER.warn("Failed to get login cookies");
                cookies.put("agegate_state","1"); // Bypasses the age gate
            }
            
        return Http.url(this.url)
                   .cookies(cookies)
                   .get();
    }
    
    /**
     * 
     * @param page
     * @param id
     * @return 
     */
    private String jsonToImage(Document page, String id) {
        Elements js = page.select("script[type=\"text/javascript\"]");
        for (Element tag : js) {
            if (tag.html().contains("window.__pageload")) {
                try {
                    String script = tag.html();
                    script = script.substring(script.indexOf("window.__pageload"));
                    if (!script.contains(id)) {
                        continue;
                    }
                    script = script.substring(script.indexOf(id));
                    // first },"src":"url" after id
                    script = script.substring(script.indexOf("},\"src\":\"") + 9, script.indexOf("\",\"type\""));
                    return script.replace("\\/", "/");
                } catch (StringIndexOutOfBoundsException e) {
                    LOGGER.debug("Unable to get json link from " + page.location());
                }
            }
        }
        return null;
    }
    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> imageURLs = new ArrayList<>();

        // Iterate over all thumbnails
        for (Element thumb : page.select("div.zones-container span.thumb")) {
            if (isStopped()) {
                break;
            }
            Element img = thumb.select("img").get(0);
            if (img.attr("transparent").equals("false")) {
                continue; // a.thumbs to other albums are invisible
            }
            // Get full-sized image via helper methods
            String fullSize = null;
            if (thumb.attr("data-super-full-img").contains("//orig")) {
                fullSize = thumb.attr("data-super-full-img");
            } else {
                String spanUrl = thumb.attr("href");
                String fullSize1 = jsonToImage(page,spanUrl.substring(spanUrl.lastIndexOf('-') + 1));
                if (fullSize1 == null || !fullSize1.contains("//orig")) {
                    fullSize = smallToFull(img.attr("src"), spanUrl);
                }
                if (fullSize == null && fullSize1 != null) {
                    fullSize = fullSize1;
                }
            }
            if (fullSize == null) {
                if (thumb.attr("data-super-full-img") != null) {
                    fullSize = thumb.attr("data-super-full-img");
                } else if (thumb.attr("data-super-img") != null) {
                    fullSize = thumb.attr("data-super-img");
                } else {
                    continue;
                }
            }
            if (triedURLs.contains(fullSize)) {
                LOGGER.warn("Already tried to download " + fullSize);
                continue;
            }
            triedURLs.add(fullSize);
            imageURLs.add(fullSize);

            if (isThisATest()) {
                // Only need one image for a test
                break;
            }
        }
        return imageURLs;
    }
    @Override
    public List<String> getDescriptionsFromPage(Document page) {
        List<String> textURLs = new ArrayList<>();
        // Iterate over all thumbnails
        for (Element thumb : page.select("div.zones-container span.thumb")) {
            LOGGER.info(thumb.attr("href"));
            if (isStopped()) {
                break;
            }
            Element img = thumb.select("img").get(0);
            if (img.attr("transparent").equals("false")) {
                continue; // a.thumbs to other albums are invisible
            }
            textURLs.add(thumb.attr("href"));

        }
        return textURLs;
    }
    @Override
    public Document getNextPage(Document page) throws IOException {
        if (isThisATest()) {
            return null;
        }
        Elements nextButtons = page.select("link[rel=\"next\"]");
        if (nextButtons.isEmpty()) {
            if (page.select("link[rel=\"prev\"]").isEmpty()) {
                throw new IOException("No next page found");
            } else {
                throw new IOException("Hit end of pages");
            }
        }
        Element a = nextButtons.first();
        String nextPage = a.attr("href");
        if (nextPage.startsWith("/")) {
            nextPage = "http://" + this.url.getHost() + nextPage;
        }
        if (!sleep(PAGE_SLEEP_TIME)) {
            throw new IOException("Interrupted while waiting to load next page: " + nextPage);
        }
        LOGGER.info("Found next page: " + nextPage);
        return Http.url(nextPage)
                   .cookies(cookies)
                   .get();
    }

    @Override
    public boolean keepSortOrder() {
         // Don't keep sort order (do not add prefixes).
         // Causes file duplication, as outlined in https://github.com/4pr0n/ripme/issues/113
        return false;
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), cookies);
        sleep(IMAGE_SLEEP_TIME);
    }

    /**
     * Tries to get full size image from thumbnail URL
     * @param thumb Thumbnail URL
     * @param throwException Whether or not to throw exception when full size image isn't found
     * @return Full-size image URL
     * @throws Exception If it can't find the full-size URL
     */
    private static String thumbToFull(String thumb, boolean throwException) throws Exception {
        thumb = thumb.replace("http://th", "http://fc");
        List<String> fields = new ArrayList<>(Arrays.asList(thumb.split("/")));
        fields.remove(4);
        if (!fields.get(4).equals("f") && throwException) {
            // Not a full-size image
            throw new Exception("Can't get full size image from " + thumb);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                result.append("/");
            }
            result.append(fields.get(i));
        }
        return result.toString();
    }

    /**
     * Attempts to download description for image.
     * Comes in handy when people put entire stories in their description.
     * If no description was found, returns null.
     * @param url The URL the description will be retrieved from
     * @param page The gallery page the URL was found on
     * @return A String[] with first object being the description, and the second object being image file name if found.
     */
    @Override
    public String[] getDescription(String url,Document page) {
        if (isThisATest()) {
            return null;
        }
        try {
            // Fetch the image page
            Response resp = Http.url(url)
                                .referrer(this.url)
                                .cookies(cookies)
                                .response();
            cookies.putAll(resp.cookies());

            // Try to find the description
            Document documentz = resp.parse();
            Element ele = documentz.select("div.dev-description").first();
            if (ele == null) {
                throw new IOException("No description found");
            }
            documentz.outputSettings(new Document.OutputSettings().prettyPrint(false));
            ele.select("br").append("\\n");
            ele.select("p").prepend("\\n\\n");
            String fullSize = null;
            Element thumb = page.select("div.zones-container span.thumb[href=\"" + url + "\"]").get(0);
            if (!thumb.attr("data-super-full-img").isEmpty()) {
                fullSize = thumb.attr("data-super-full-img");
                String[] split = fullSize.split("/");
                fullSize = split[split.length - 1];
            } else {
                String spanUrl = thumb.attr("href");
                fullSize = jsonToImage(page,spanUrl.substring(spanUrl.lastIndexOf('-') + 1));
                if (fullSize != null) {
                    String[] split = fullSize.split("/");
                    fullSize = split[split.length - 1];
                }
            }
            if (fullSize == null) {
                return new String[] {Jsoup.clean(ele.html().replaceAll("\\\\n", System.getProperty("line.separator")), "", Whitelist.none(), new Document.OutputSettings().prettyPrint(false))};
            }
            fullSize = fullSize.substring(0, fullSize.lastIndexOf("."));
            return new String[] {Jsoup.clean(ele.html().replaceAll("\\\\n", System.getProperty("line.separator")), "", Whitelist.none(), new Document.OutputSettings().prettyPrint(false)),fullSize};
            // TODO Make this not make a newline if someone just types \n into the description.
        } catch (IOException ioe) {
                LOGGER.info("Failed to get description at " + url + ": '" + ioe.getMessage() + "'");
                return null;
        }
    }

    /**
     * If largest resolution for image at 'thumb' is found, starts downloading
     * and returns null.
     * If it finds a larger resolution on another page, returns the image URL.
     * @param thumb Thumbnail URL
     * @param page Page the thumbnail is retrieved from
     * @return Highest-resolution version of the image based on thumbnail URL and the page.
     */
    private String smallToFull(String thumb, String page) {
        try {
            // Fetch the image page
            Response resp = Http.url(page)
                                .referrer(this.url)
                                .cookies(cookies)
                                .response();
            cookies.putAll(resp.cookies());
            Document doc = resp.parse();
            Elements els = doc.select("img.dev-content-full");
            String fsimage = null;
            // Get the largest resolution image on the page
            if (!els.isEmpty()) {
                // Large image
                fsimage = els.get(0).attr("src");
                LOGGER.info("Found large-scale: " + fsimage);
                if (fsimage.contains("//orig")) {
                    return fsimage;
                }
            }
            // Try to find the download button
            els = doc.select("a.dev-page-download");
            if (!els.isEmpty()) {
                // Full-size image
                String downloadLink = els.get(0).attr("href");
                LOGGER.info("Found download button link: " + downloadLink);
                HttpURLConnection con = (HttpURLConnection) new URL(downloadLink).openConnection();
                con.setRequestProperty("Referer",this.url.toString());
                String cookieString = "";
                for (Map.Entry<String, String> entry : cookies.entrySet()) {
                    cookieString = cookieString + entry.getKey() + "=" + entry.getValue() + "; ";
                }
                cookieString = cookieString.substring(0,cookieString.length() - 1);
                con.setRequestProperty("Cookie",cookieString);
                con.setRequestProperty("User-Agent", USER_AGENT);
                con.setInstanceFollowRedirects(true);
                con.connect();
                int code = con.getResponseCode();
                String location = con.getURL().toString();
                con.disconnect();
                if (location.contains("//orig")) {
                    fsimage = location;
                    LOGGER.info("Found image download: " + location);
                }
            }
            if (fsimage != null) {
                return fsimage;
            }
            throw new IOException("No download page found");
        } catch (IOException ioe) {
            try {
                LOGGER.info("Failed to get full size download image at " + page + " : '" + ioe.getMessage() + "'");
                String lessThanFull = thumbToFull(thumb, false);
                LOGGER.info("Falling back to less-than-full-size image " + lessThanFull);
                return lessThanFull;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Returns DA cookies.
     * @return Map of cookies containing session data.
     */
    private Map<String, String> getDACookies() {
        return RipUtils.getCookiesFromString(Utils.getConfigString("deviantart.cookies", loginCookies));
    }
}