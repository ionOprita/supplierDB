(function () {
  function normalizePath(path) {
    return path.replace(/\/+$/, '') || '/';
  }

  function markCurrentPage(navigation) {
    const links = Array.from(navigation.querySelectorAll('a.app-nav-link[href]'));
    let path = normalizePath(window.location.pathname);
    if (/^\/private\/products(?:\/|$)/.test(path)) path = '/private/products';
    else if (/^\/private\/ads-[^/]+(?:\/|$)/.test(path)) path = '/private/ads-campaigns';
    else if (/^\/admin\/db-explorer(?:\/|$)/.test(path)) path = '/admin/db-explorer/products';

    let current = null;
    let longest = -1;
    for (const link of links) {
      const url = new URL(link.getAttribute('href'), window.location.href);
      const candidate = normalizePath(url.pathname);
      if (url.origin === window.location.origin && (candidate === path || path.startsWith(candidate + '/'))
          && candidate.length > longest) {
        current = link;
        longest = candidate.length;
      }
    }
    for (const link of links) {
      const active = link === current;
      link.classList.toggle('is-active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
  }

  function initialize() {
    const body = document.body;
    if (!body || !body.classList.contains('app-shell')) return;
    const navigation = document.getElementById('appNavigation');
    if (navigation) markCurrentPage(navigation);

    const sidebar = document.getElementById('appSidebar');
    const toggle = document.getElementById('appNavToggle');
    const closeButton = document.getElementById('appNavClose');
    const backdrop = document.getElementById('appNavBackdrop');
    if (!sidebar || !toggle || !navigation || !backdrop || typeof window.matchMedia !== 'function') return;

    const mobile = window.matchMedia('(max-width: 1199px)');
    const backgrounds = ['appHeader', 'appMain', 'appFooter']
      .map(id => document.getElementById(id)).filter(Boolean);
    const originalAttributes = new Map(['role', 'aria-modal', 'aria-labelledby', 'aria-label', 'aria-hidden']
      .map(name => [name, sidebar.getAttribute(name)]));
    let open = false;
    let backgroundStates = null;
    let previousOverflow;

    function nativeDialogOpen() {
      return document.querySelector('dialog[open]') !== null;
    }

    function focusableElements() {
      return Array.from(sidebar.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'))
        .filter(element => !element.disabled && element.tabIndex >= 0
          && !element.closest('[hidden], [inert]') && element.getClientRects().length > 0);
    }

    function restoreSidebarAttributes() {
      for (const [name, value] of originalAttributes) {
        if (value === null) sidebar.removeAttribute(name);
        else sidebar.setAttribute(name, value);
      }
    }

    function closeDrawer(restoreFocus = true) {
      const wasOpen = open;
      open = false;
      body.classList.remove('app-nav-open');
      toggle.setAttribute('aria-expanded', 'false');
      backdrop.hidden = true;
      restoreSidebarAttributes();
      sidebar.inert = mobile.matches;
      if (mobile.matches) sidebar.setAttribute('aria-hidden', 'true');
      if (backgroundStates) {
        for (const [element, inert] of backgroundStates) element.inert = inert;
        backgroundStates = null;
        body.style.overflow = previousOverflow;
      }
      if (wasOpen && restoreFocus && mobile.matches && !nativeDialogOpen()) toggle.focus();
    }

    function openDrawer() {
      if (!mobile.matches || open || nativeDialogOpen()) return;
      open = true;
      body.classList.add('app-nav-open');
      toggle.setAttribute('aria-expanded', 'true');
      sidebar.inert = false;
      sidebar.removeAttribute('aria-hidden');
      sidebar.setAttribute('role', 'dialog');
      sidebar.setAttribute('aria-modal', 'true');
      if (document.getElementById('appNavTitle')) sidebar.setAttribute('aria-labelledby', 'appNavTitle');
      else if (!sidebar.getAttribute('aria-label')) sidebar.setAttribute('aria-label', 'Navigation');
      backdrop.hidden = false;
      backgroundStates = backgrounds.map(element => [element, element.inert]);
      for (const [element] of backgroundStates) element.inert = true;
      previousOverflow = body.style.overflow;
      body.style.overflow = 'hidden';
      (closeButton || focusableElements()[0] || sidebar).focus();
    }

    function synchronizeLayout() {
      const focused = document.activeElement;
      const sidebarHadFocus = sidebar.contains(focused);
      closeDrawer(false);
      if (!sidebarHadFocus || nativeDialogOpen()) return;
      if (mobile.matches) toggle.focus();
      else if (focused === closeButton) {
        const current = navigation.querySelector('a[aria-current="page"]');
        (current || focusableElements()[0] || sidebar).focus();
      }
    }

    toggle.addEventListener('click', () => open ? closeDrawer() : openDrawer());
    if (closeButton) closeButton.addEventListener('click', () => closeDrawer());
    backdrop.addEventListener('click', () => closeDrawer());
    navigation.addEventListener('click', event => {
      const link = event.target.closest('a[href]');
      if (link && navigation.contains(link)) closeDrawer();
    });
    document.addEventListener('keydown', event => {
      if (!open || !mobile.matches || event.defaultPrevented || nativeDialogOpen()) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        closeDrawer();
      } else if (event.key === 'Tab') {
        const elements = focusableElements();
        const first = elements[0];
        const last = elements[elements.length - 1];
        if (!first) {
          event.preventDefault();
          sidebar.focus();
        } else if (!sidebar.contains(document.activeElement)
            || (event.shiftKey && document.activeElement === first)
            || (!event.shiftKey && document.activeElement === last)) {
          event.preventDefault();
          (event.shiftKey ? last : first).focus();
        }
      }
    });
    if (typeof mobile.addEventListener === 'function') mobile.addEventListener('change', synchronizeLayout);
    else if (typeof mobile.addListener === 'function') mobile.addListener(synchronizeLayout);
    body.classList.add('app-shell--enhanced');
    synchronizeLayout();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, {once: true});
  else initialize();
}());
