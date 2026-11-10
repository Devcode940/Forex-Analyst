package com.devcode940.ForexAnalyst.data.utils

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Pagination configuration for database queries.
 * 
 * @param pageSize Number of items per page
 * @param initialPage Starting page (default: 0 or 1)
 * @param prefetchDistance How many items from the end to trigger next page load
 * @param maxPages Maximum number of pages to load (null for unlimited)
 */
data class PaginationConfig(
    val pageSize: Int = 20,
    val initialPage: Int = 0,
    val prefetchDistance: Int = 5,
    val maxPages: Int? = null
) {
    companion object {
        /**
         * Default pagination configuration.
         */
        val DEFAULT = PaginationConfig()

        /**
         * Small pagination for quick loading.
         */
        val SMALL = PaginationConfig(pageSize = 10, prefetchDistance = 3)

        /**
         * Large pagination for bulk loading.
         */
        val LARGE = PaginationConfig(pageSize = 50, prefetchDistance = 10)

        /**
         * No pagination (load all).
         */
        val NONE = PaginationConfig(pageSize = Int.MAX_VALUE)
    }

    /**
     * Calculate the offset for a given page.
     */
    fun offsetForPage(page: Int): Int = page * pageSize

    /**
     * Calculate the limit for a query.
     */
    fun limit(): Int = pageSize

    /**
     * Check if we should load the next page.
     */
    fun shouldLoadNext(currentIndex: Int, totalCount: Int): Boolean {
        if (maxPages != null && (initialPage + (totalCount / pageSize) + 1) >= maxPages) {
            return false
        }
        return currentIndex >= totalCount - prefetchDistance
    }
}

/**
 * Result of a paginated query.
 * 
 * @param items The items in the current page
 * @param totalCount Total number of items (if available)
 * @param currentPage Current page number
 * @param pageSize Number of items per page
 * @param hasMore Whether there are more items to load
 */
data class PaginatedResult<T>(
    val items: List<T>,
    val totalCount: Int? = null,
    val currentPage: Int = 0,
    val pageSize: Int = 20,
    val hasMore: Boolean = false
) {
    /**
     * Check if this is the first page.
     */
    val isFirstPage: Boolean get() = currentPage == 0

    /**
     * Check if this is the last page.
     */
    val isLastPage: Boolean get() = !hasMore

    /**
     * Get the next page number.
     */
    fun nextPage(): Int = currentPage + 1

    /**
     * Get the previous page number.
     */
    fun previousPage(): Int = maxOf(currentPage - 1, 0)

    /**
     * Check if there are items.
     */
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * Check if there are items.
     */
    val isNotEmpty: Boolean get() = items.isNotEmpty()

    /**
     * Get the total number of pages.
     */
    fun totalPages(): Int? = totalCount?.let { (it + pageSize - 1) / pageSize }

    /**
     * Append another paginated result.
     */
    fun append(next: PaginatedResult<T>): PaginatedResult<T> {
        return PaginatedResult(
            items = items + next.items,
            totalCount = next.totalCount ?: totalCount,
            currentPage = next.currentPage,
            pageSize = pageSize,
            hasMore = next.hasMore
        )
    }
}

/**
 * Pagination state for Compose.
 */
data class PaginationState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: Throwable? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 0
) {
    companion object {
        val IDLE = PaginationState()
        val LOADING = PaginationState(isLoading = true)
        
        fun error(error: Throwable) = PaginationState(error = error)
        fun loadingMore(currentPage: Int) = PaginationState(
            isLoadingMore = true,
            currentPage = currentPage
        )
    }

    val isIdle: Boolean get() = !isLoading && !isLoadingMore && error == null
}

/**
 * Pagination helper for Flow-based data sources.
 * 
 * @param config Pagination configuration
 * @param fetch Function to fetch a page of data
 */
class PaginationHelper<T>(
    private val config: PaginationConfig = PaginationConfig.DEFAULT,
    private val fetch: suspend (page: Int, pageSize: Int) -> List<T>
) {
    private var currentPage = config.initialPage
    private var hasMore = true
    private var isLoading = false

    /**
     * Reset pagination state.
     */
    fun reset() {
        currentPage = config.initialPage
        hasMore = true
        isLoading = false
    }

    /**
     * Load the first page.
     */
    suspend fun loadFirstPage(): PaginatedResult<T> {
        reset()
        return loadNextPage()
    }

    /**
     * Load the next page.
     */
    suspend fun loadNextPage(): PaginatedResult<T> {
        if (!hasMore || isLoading) {
            return PaginatedResult(
                items = emptyList(),
                currentPage = currentPage,
                pageSize = config.pageSize,
                hasMore = false
            )
        }

        isLoading = true
        try {
            val offset = config.offsetForPage(currentPage)
            val items = fetch(currentPage, config.pageSize)
            
            hasMore = items.size == config.pageSize
            val result = PaginatedResult(
                items = items,
                currentPage = currentPage,
                pageSize = config.pageSize,
                hasMore = hasMore
            )
            
            currentPage++
            isLoading = false
            
            return result
        } catch (e: Exception) {
            isLoading = false
            throw e
        }
    }

    /**
     * Load a specific page.
     */
    suspend fun loadPage(page: Int): PaginatedResult<T> {
        if (page < config.initialPage) {
            throw IllegalArgumentException("Page must be >= ${config.initialPage}")
        }
        
        currentPage = page
        isLoading = true
        try {
            val offset = config.offsetForPage(page)
            val items = fetch(page, config.pageSize)
            
            hasMore = items.size == config.pageSize
            val result = PaginatedResult(
                items = items,
                currentPage = page,
                pageSize = config.pageSize,
                hasMore = hasMore
            )
            
            isLoading = false
            
            return result
        } catch (e: Exception) {
            isLoading = false
            throw e
        }
    }

    /**
     * Get the current pagination state.
     */
    fun getState(): PaginationState {
        return PaginationState(
            isLoading = isLoading,
            hasMore = hasMore,
            currentPage = currentPage
        )
    }
}

/**
 * Create a paginated flow from a list.
 * 
 * @param config Pagination configuration
 * @param items The full list of items
 */
fun <T> List<T>.asPaginatedFlow(
    config: PaginationConfig = PaginationConfig.DEFAULT
): Flow<PaginatedResult<T>> = flow {
    var currentPage = config.initialPage
    var hasMore = true
    
    while (hasMore) {
        val offset = config.offsetForPage(currentPage)
        val pageItems = this@asPaginatedFlow.drop(offset).take(config.pageSize)
        
        hasMore = offset + pageItems.size < this@asPaginatedFlow.size
        
        emit(PaginatedResult(
            items = pageItems,
            totalCount = this@asPaginatedFlow.size,
            currentPage = currentPage,
            pageSize = config.pageSize,
            hasMore = hasMore
        ))
        
        currentPage++
    }
}

/**
 * Extension function to paginate a list.
 */
fun <T> List<T>.paginate(
    page: Int = 0,
    pageSize: Int = 20
): PaginatedResult<T> {
    val offset = page * pageSize
    val items = this.drop(offset).take(pageSize)
    val hasMore = offset + items.size < this.size
    
    return PaginatedResult(
        items = items,
        totalCount = this.size,
        currentPage = page,
        pageSize = pageSize,
        hasMore = hasMore
    )
}

/**
 * Extension function to get a page from a list.
 */
fun <T> List<T>.getPage(
    page: Int = 0,
    pageSize: Int = 20
): List<T> {
    val offset = page * pageSize
    return this.drop(offset).take(pageSize)
}

/**
 * Create a paginated result from a list and page info.
 */
fun <T> paginatedResultOf(
    items: List<T>,
    page: Int = 0,
    pageSize: Int = 20,
    totalCount: Int? = null
): PaginatedResult<T> {
    val hasMore = items.size == pageSize && totalCount != items.size
    
    return PaginatedResult(
        items = items,
        totalCount = totalCount,
        currentPage = page,
        pageSize = pageSize,
        hasMore = hasMore
    )
}
