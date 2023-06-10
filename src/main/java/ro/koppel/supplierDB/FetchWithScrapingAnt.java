package ro.koppel.supplierDB;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;

public class FetchWithScrapingAnt {
    public static void main(String[] args) throws IOException {
        var found = false;
        var page = 0;
        do {
            page++;
            System.out.println("PAGE " + page);
            String earchTerm = URLEncoder.encode("smartwatch camera");
            String url = buildScrapingAntURL("https://www.globalsources.com/searchList/suppliers?keyWord=" + earchTerm + "&pageNum=" + page, "8e2b076fd0a742a4abbc1a52bec5a456");
            System.out.println(url);
            Document document = Jsoup.connect(url).timeout(2 * 60 * 1000).get();
            Elements supplierElements = document.select(".mod-supp-info");
            found = supplierElements.size() > 0;
            // Extract the supplier names
            for (Element element : supplierElements) {
                var aTags = element.children().stream().filter(x -> x.tag().getName().equals("a")).toList();
                if (aTags.size() == 0) {
                    System.out.println("WARN no a tags found");
                    found = false;
                }
                //System.out.println(aTags);
                var titleTags = aTags.stream().filter(x -> x.hasClass("tit")).toList();
                if (titleTags.size() == 0) {
                    System.out.println("WARN no a tags with class tit found");
                    found = false;
                }
                //System.out.println(titleTags);
                var link = titleTags.get(0).attr("href");
                System.out.println("LINK = " + link);
            }
        } while (found);

    }

    public static String buildScrapingAntURL(String url, String apiKey) {
        return "https://api.scrapingant.com/v2/general?url=" + URLEncoder.encode(url) + "&x-api-key=" + apiKey + "&proxy_copuntry=CN";
    }
}
