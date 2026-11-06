@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName,userRole = userRole,pageTitle = pageTitle+" " + userName, content = @`
    <section class="auth-pending-card" aria-labelledby="activationTitle">
        <h2 id="activationTitle">You're almost there</h2>
        <p>Your account <span class="auth-pending-name">${userName}</span> is ready and waiting for activation.</p>
        <p>Ask an administrator to activate your account. Once approved, sign out and sign in again to open your workspace.</p>
        <p>You can use the Logout button above to switch accounts.</p>
    </section>
`)
