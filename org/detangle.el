#!/usr/bin/env -S emacs -Q --script

;;==========================================================
;; First make sure that org-mode is installed
;;==========================================================

(require 'package)

(setq package-selected-packages '(org)
      package-archives          '(("gnu"          . "https://elpa.gnu.org/packages/")
                                  ("marmalade"    . "https://marmalade-repo.org/packages/")
                                  ("melpa-stable" . "https://stable.melpa.org/packages/")
                                  ("melpa"        . "https://melpa.org/packages/")))

(package-initialize)

(unless package-archive-contents
  (package-refresh-contents))

(package-install-selected-packages)

;;==========================================================
;; Now load all files under the specified root-directory
;; and detangle each one back into GridFire.org
;;==========================================================

(require 'org)

;; Prevent extra whitespace from being injected into code blocks
(setq org-src-preserve-indentation t)

(defun valid-directory-p (directory-name)
  (and directory-name (file-directory-p directory-name)))

(defvar detangle-file-regexp (string-join '("\\.txt$"
                                            "\\.org$"
                                            "\\.edn$"
                                            "\\pom.xml$"
                                            "\\.el$"
                                            "\\.sh$"
                                            "\\.properties$"
                                            "\\.clj$"
                                            "\\.sql$")
                                          "\\|"))

(let ((root-directory (car argv)))
  (if (not (valid-directory-p root-directory))
      (princ "Usage: detangle.el <source-directory>\n")
    (let ((script-directory  (file-name-directory load-file-name))
          (files-to-detangle (seq-remove (lambda (file-name) (string-match-p "/target/\\|/classes/" file-name))
                                         (directory-files-recursively root-directory detangle-file-regexp))))
      ;; Enter the org/ directory and set the current buffer to GridFire.org so that (save-buffer) works later
      (cd script-directory)
      (find-file "./GridFire.org")
      (mapc (lambda (source-file)
              (org-babel-detangle source-file)
              (save-buffer)) ; Save GridFire.org after each detangling operation
            files-to-detangle))))

;;==========================================================
;; Obligatory calling shell protection
;;==========================================================

(setq argv nil)
