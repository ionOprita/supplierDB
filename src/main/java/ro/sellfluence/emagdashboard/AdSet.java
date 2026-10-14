package ro.sellfluence.emagdashboard;

import java.util.List;

public record AdSet(
        AdsAdset adSet,
        List<AdsSearchPhrase> searchPrases,
        List<AdsTargetedProduct> targetedProducts,
        List<AdsKeyword> keywords) {
}
