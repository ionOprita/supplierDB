package ro.sellfluence.emagapi;

public record CountResponse (
        int noOfItems,
        int noOfPages,
        int itemsPerPage
)
{}
