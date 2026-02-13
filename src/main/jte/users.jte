@import java.util.List
@import ro.sellfluence.db.PassKey.AdminUser
@import ro.sellfluence.db.PassKey.Role

@param String userName
@param String userRole
@param String pageTitle
@param List<AdminUser> users
@param String message
@param String error

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(message != null)
        <p><strong>${message}</strong></p>
    @endif
    @if(error != null)
        <p><strong>${error}</strong></p>
    @endif
    <div class="wrap">
        <table>
            <thead>
            <tr>
                <th>Name</th>
                <th>Current role</th>
                <th>Change role</th>
                <th>Delete user</th>
            </tr>
            </thead>
            <tbody>
            @if(users.isEmpty())
                <tr>
                    <td colspan="4">No users found.</td>
                </tr>
            @else
                @for(var managedUser : users)
                    <tr>
                        <td>${managedUser.username()}</td>
                        <td>${managedUser.role().name()}</td>
                        <td>
                            <form action="/admin/users/${managedUser.id()}/role" method="post" style="display:flex; gap:.4rem; align-items:center;">
                                <select name="role">
                                    @for(var roleOption : Role.values())
                                        @if(roleOption == managedUser.role())
                                            <option value="${roleOption.name()}" selected>${roleOption.name()}</option>
                                        @else
                                            <option value="${roleOption.name()}">${roleOption.name()}</option>
                                        @endif
                                    @endfor
                                </select>
                                <button type="submit">Save</button>
                            </form>
                        </td>
                        <td>
                            @if(managedUser.username().equals(userName))
                                <button type="button" disabled title="You cannot delete your own account">Current user</button>
                            @else
                                <form action="/admin/users/${managedUser.id()}/delete" method="post" onsubmit="return confirm('Delete user ${managedUser.username()}?');">
                                    <button type="submit">Delete</button>
                                </form>
                            @endif
                        </td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>
`)
