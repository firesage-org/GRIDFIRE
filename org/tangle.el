#!/usr/bin/env -S emacs -Q --script

;;==========================================================
;; First make sure that org and clojure-mode are installed
;;==========================================================

(require 'package)

(setq package-selected-packages '(org clojure-mode)
      package-archives          '(("gnu"          . "https://elpa.gnu.org/packages/")
                                  ("marmalade"    . "https://marmalade-repo.org/packages/")
                                  ("melpa-stable" . "https://stable.melpa.org/packages/")
                                  ("melpa"        . "https://melpa.org/packages/")))

(package-initialize)

(unless package-archive-contents
  (package-refresh-contents))

(package-install-selected-packages)

;;==========================================================
;; Now load GridFire.org and tangle its code blocks to disk
;;==========================================================

(require 'org)
(require 'clojure-mode)

(find-file "GridFire.org")
(org-babel-tangle)

;;==========================================================
;; Obligatory calling shell protection
;;==========================================================

(setq argv nil)
