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
                <div class="wrap">
                    <table class="log-files-table">
                        <thead>
                        <tr>
                            <th>File</th>
                            <th class="numeric">Size (bytes)</th>
                            <th>Last modified</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        @if(section.files().isEmpty())
                            <tr>
                                <td colspan="4">No log files found.</td>
                            </tr>
                        @else
                            @for(var logFile : section.files())
                                <tr>
                                    <td><code>${logFile.name()}</code></td>
                                    <td class="numeric">${logFile.size()}</td>
                                    <td>${logFile.lastModified().toString()}</td>
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
`)
