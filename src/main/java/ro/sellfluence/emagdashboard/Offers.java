package ro.sellfluence.emagdashboard;

import java.util.List;

public record Offers(List<Offer> items, Integer totalNumberOfItems) {
}
