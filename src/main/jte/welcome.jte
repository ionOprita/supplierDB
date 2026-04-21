@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName,userRole = userRole,pageTitle = pageTitle+" " + userName, content = @`
    <p>Your account ${userName} was created but not yet activated.</p>
    <p>Please ask an administrator to activate ${userName}.</p>
`)
