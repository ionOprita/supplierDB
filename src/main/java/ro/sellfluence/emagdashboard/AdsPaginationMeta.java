package ro.sellfluence.emagdashboard;

public record AdsPaginationMeta(
        Integer totalCount,
        Integer page,
        Integer perPage,
        Integer pageCount
) {
}
