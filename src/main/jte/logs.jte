@import java.util.List
@import ro.sellfluence.app.ServerLogFiles.Entry

@param String userName
@param String userRole
@param String pageTitle
@param List<Entry> logFiles
@param String error

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(error != null)
        <p class="flash-message error-message" role="alert">${error}</p>
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
                @if(logFiles.isEmpty())
                    <tr>
                        <td colspan="4">No log files found.</td>
                    </tr>
                @else
                    @for(var logFile : logFiles)
                        <tr>
                            <td><code>${logFile.name()}</code></td>
                            <td class="numeric">${logFile.size()}</td>
                            <td>${logFile.lastModified().toString()}</td>
                            <td class="log-file-actions">
                                <a href="/admin/logs/view?file=${logFile.encodedName()}" target="_blank" rel="noopener">View</a>
                                <a href="/admin/logs/download?file=${logFile.encodedName()}">Download</a>
                            </td>
                        </tr>
                    @endfor
                @endif
                </tbody>
            </table>
        </div>
    @endif
`)
