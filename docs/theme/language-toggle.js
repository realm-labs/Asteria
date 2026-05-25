(function () {
  function normalizedRelativePath() {
    var relative = relativePathFromLocation(window.location);

    if (relative === "" || relative.endsWith("/")) {
      relative += "index.html";
    }
    return relative;
  }

  function pageExists(relativePath) {
    var knownPages = new Set([
      "index.html",
      "module-map.html",
      "application-lifecycle.html",
      "contribution.html",
      "events.html",
      "config.html",
      "cluster-pekko.html",
      "messaging-protocol-gateway.html",
      "persistence.html",
      "script-and-gm.html",
      "patch.html",
      "observability-and-starter.html"
    ]);

    var englishPath = relativePath.startsWith("zh/")
      ? relativePath.slice(3)
      : relativePath;
    return knownPages.has(englishPath);
  }

  function targetFor(relativePath) {
    if (relativePath.startsWith("zh/")) {
      return relativePath.slice(3);
    }
    return "zh/" + relativePath;
  }

  function addLanguageToggle() {
    var relative = normalizedRelativePath();
    if (!pageExists(relative)) {
      return;
    }

    var isChinese = relative.startsWith("zh/");
    var link = document.createElement("a");
    var root = typeof path_to_root === "string" ? path_to_root : "";
    link.href = root + targetFor(relative);
    link.textContent = isChinese ? "English" : "中文";
    link.setAttribute("aria-label", isChinese ? "Switch to English" : "切换到中文");
    link.style.marginLeft = "0.75rem";
    link.style.padding = "0.2rem 0.55rem";
    link.style.border = "1px solid var(--icons)";
    link.style.borderRadius = "4px";
    link.style.fontSize = "0.85rem";
    link.style.textDecoration = "none";

    var rightButtons = document.querySelector(".right-buttons");
    if (rightButtons) {
      rightButtons.prepend(link);
      return;
    }

    var menuBar = document.getElementById("menu-bar");
    if (menuBar) {
      menuBar.appendChild(link);
    }
  }

  function relativePathFromLocation(location) {
    return relativePathFromPath(location.pathname);
  }

  function relativePathFromPath(path) {
    var marker = "/Asteria/";
    var index = path.indexOf(marker);
    if (index >= 0) {
      return path.slice(index + marker.length);
    }

    var bookIndex = path.indexOf("/docs/book/");
    if (bookIndex >= 0) {
      return path.slice(bookIndex + "/docs/book/".length);
    }

    var root = typeof path_to_root === "string" ? path_to_root : "";
    var rootPath = new URL(root || ".", window.location.href).pathname;
    if (!rootPath.endsWith("/")) {
      rootPath += "/";
    }
    if (path.startsWith(rootPath)) {
      return path.slice(rootPath.length);
    }

    return path.replace(/^\/+/, "");
  }

  function languageForPath(path) {
    var relative = relativePathFromPath(path);
    return relative.startsWith("zh/") ? "zh" : "en";
  }

  function filterSidebar() {
    var currentLanguage = languageForPath(window.location.pathname);
    var sidebar = document.getElementById("mdbook-sidebar") || document.getElementById("sidebar");
    if (!sidebar) {
      return;
    }

    var chapter = sidebar.querySelector("ol.chapter");
    if (!chapter) {
      return;
    }

    Array.from(chapter.children).forEach(function (item) {
      if (item.classList.contains("part-title")) {
        item.hidden = true;
        return;
      }

      if (item.querySelector(".part-title")) {
        item.hidden = true;
        return;
      }

      var link = item.querySelector(".chapter-link-wrapper > a");
      if (!link) {
        item.hidden = true;
        return;
      }

      var linkLanguage = languageForPath(new URL(link.href, window.location.href).pathname);
      item.hidden = linkLanguage !== currentLanguage;
    });
  }

  function initialize() {
    addLanguageToggle();
    filterSidebar();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initialize);
  } else {
    initialize();
  }
})();
