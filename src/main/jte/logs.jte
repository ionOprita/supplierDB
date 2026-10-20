@import java.util.List
@import ro.sellfluence.app.ServerLogFiles.Section

@param String userName
@param String userRole
@param String pageTitle
@param List<Section> logSections

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @for(var section : logSections)
        <section class="log-files-section">
            <h2>${section.title()}</h2>
            <p class="muted">${section.description()}</p>
            @if(section.error() != null)
                <p class="flash-message error-message" role="alert">${section.error()}</p>
            @else
                <div class="page-actions log-filter-controls" role="group" aria-label="Filter ${section.title()}">
                    <span>Filter by:</span>
                    <div class="log-filter-field">
                        <label for="logNameFilter-${section.source()}">Name</label>
                        <input id="logNameFilter-${section.source()}" class="log-name-filter" type="search" autocomplete="off" />
                    </div>
                </div>
                <div class="wrap">
                    <table class="log-files-table">
                        <thead>
                        <tr>
                            <th scope="col"><button type="button" class="log-sort-button" data-sort-column="0" aria-label="Sort by File">File</button></th>
                            <th scope="col" class="numeric"><button type="button" class="log-sort-button" data-sort-column="1" aria-label="Sort by Size">Size (bytes)</button></th>
                            <th scope="col" aria-sort="descending"><button type="button" class="log-sort-button" data-sort-column="2" aria-label="Sort by Last modified">Last modified</button></th>
                            <th scope="col">Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        @if(section.files().isEmpty())
                            <tr>
                                <td colspan="4">No log files found.</td>
                            </tr>
                        @else
                            @for(var logFile : section.files())
                                <tr data-log-file>
                                    <td><code>${logFile.name()}</code></td>
                                    <td class="numeric" data-sort-value="${logFile.size()}">${logFile.size()}</td>
                                    <td data-sort-value="${logFile.lastModified().toEpochMilli()}">${logFile.lastModified().toString()}</td>
                                    <td class="log-file-actions">
                                        <a href="/admin/logs/view?source=${section.source()}&amp;file=${logFile.encodedName()}" target="_blank" rel="noopener">View</a>
                                        <a href="/admin/logs/download?source=${section.source()}&amp;file=${logFile.encodedName()}">Download</a>
                                    </td>
                                </tr>
                            @endfor
                        @endif
                        </tbody>
                    </table>
                </div>
            @endif
        </section>
    @endfor
    <script type="module" src="/js/logs.js"></script>
`)
