package ro.sellfluence.emagapi;

import java.util.List;

public record AdSet(
        AdsAdset adSet,
        List<AdsSearchPhrase> searchPrases,
        List<AdsTargetedProduct> targetedProducts,
        List<AdsKeyword> keywords) {
}
