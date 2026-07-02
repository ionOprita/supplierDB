package ro.sellfluence.emagapi;

public record AdsPaginationMeta(
        Integer totalCount,
        Integer page,
        Integer perPage,
        Integer pageCount
) {
}
