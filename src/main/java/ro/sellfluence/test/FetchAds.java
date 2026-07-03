package ro.sellfluence.test;

import com.bastiaanjansen.otp.TOTPGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import ro.sellfluence.emagapi.AdsAnalyticsResponse;
import ro.sellfluence.emagapi.AdsCampaign;
import ro.sellfluence.emagapi.AdsCampaignPhrasesResponse;
import ro.sellfluence.emagapi.AdsCampaignTargetedProductsResponse;
import ro.sellfluence.emagapi.AdsCampaignsResponse;
import ro.sellfluence.support.UserPassword;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Random;

import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

public class FetchAds {
    static void main() throws Exception {
        fetchFrom("sellfusion");
    }
    static final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(
                    new SimpleModule()
                            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
            ).build();

    public static String genOTP(String otpAuth) {
        var otpUri = URI.create(otpAuth);
        try {
            TOTPGenerator totp = TOTPGenerator.fromURI(otpUri);
            return totp.now();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Random random = new Random();

    private static void randomWait(Double fromSec, Double toSec) {
        var waitSec = fromSec + (toSec - fromSec) * random.nextDouble();
        try {
            Thread.sleep((long) waitSec * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    private static String getJSON(Page page, String url) {
        return page.request().get(url).text();
    }

    public static void fetchFrom(String alias) throws IOException {
        var user = UserPassword.findAlias(alias);
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false))) {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                login(page, user);
                downloadData(page);
            }
        }
    }

    private static final Path targetDir = Path.of("AdsJSON");

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }

    private static void downloadData(Page page) throws IOException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        var adsAnalyticsJSON = getJSON(page, "https://advertising.emag.net/api/v1/analytics/campaign?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=spent&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-05-20&dateEnd=2026-06-19");
        var adsAnalytics = objectMapper.readValue(adsAnalyticsJSON, AdsAnalyticsResponse.class);
        IO.println(adsAnalytics);
        IO.println(adsAnalytics.data().summary());
        var adsCampaignJSON = getJSON(page, "https://advertising.emag.net/api/v1/campaigns?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=spent&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-05-20&dateEnd=2026-06-19");
        var adsCampaign = objectMapper.readValue(adsCampaignJSON, AdsCampaignsResponse.class);
        IO.println(adsCampaign);
        adsCampaign.data().campaigns().stream().map(AdsCampaign::name).forEach(IO::println);
        var adsCampaignPhrasesJSON = getJSON(page, "https://advertising.emag.net/api/v1/campaigns/174138/search-phrases?adsetId=180461&page=1&perPage=100&sort%5B0%5D%5Bfield%5D=impressions&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-06-01&dateEnd=2026-06-30");
        Files.writeString(targetDir.resolve("adsCampaignPhrases.json"), adsCampaignPhrasesJSON);
        var adsCampaignPhrases = objectMapper.readValue(adsCampaignPhrasesJSON, AdsCampaignPhrasesResponse.class);
        IO.println(adsCampaignPhrases);
        var adsCampaignTargetedProductsJSON = getJSON(page, "https://advertising.emag.net/api/v1/campaigns/174138/adsets/180461/targeted-products?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=clicks&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-06-01&dateEnd=2026-06-30");
        Files.writeString(targetDir.resolve("adsCampaignTargetedProducts.json"), adsCampaignTargetedProductsJSON);
        var adsCampaignTargetedProducts = objectMapper.readValue(adsCampaignTargetedProductsJSON, AdsCampaignTargetedProductsResponse.class);
        IO.println(adsCampaignTargetedProducts);
    }

    private static void login(Page page, UserPassword user) {
        page.navigate("https://auth.emag.net/login?adk=PQ02EfRdOtbhV7N2");
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your username or e-mail")).dblclick();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your username or e-mail")).fill(user.getUsername());
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).fill(user.getPassword());
        randomWait(2.0, 2.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Introdu OTP")).click();
        randomWait(2.0, 7.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Introdu OTP")).fill(genOTP(user.getOtpAuth()));
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Logare")).click();
        randomWait(2.0, 2.0);
        page.navigate("https://marketplace.emag.ro/dashboard");
        randomWait(2.0, 2.0);
        page.navigate("https://advertising.emag.net/");
    }
}
