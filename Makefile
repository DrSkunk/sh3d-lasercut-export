# Convenience wrapper around build.xml. All real work happens in Ant.
#
# Usage:
#   make              # build dist/LasercutExport.sh3p
#   make clean        # remove build/ and dist/
#   make install      # copy .sh3p into the local Sweet Home 3D plugins folder
#   make reinstall    # clean + build + install
#   make run          # launch Sweet Home 3D (macOS only)
#   make bump-major   # 1.2.3 → 2.0.0, commit, tag vX.Y.Z
#   make bump-minor   # 1.2.3 → 1.3.0, commit, tag vX.Y.Z
#   make bump-patch   # 1.2.3 → 1.2.4, commit, tag vX.Y.Z
#
# Override the SDK location:
#   make SH3D_JAR=/path/to/SweetHome3D.jar

# --- Configuration -----------------------------------------------------------

PLUGIN_NAME := LasercutExport
SH3P        := dist/$(PLUGIN_NAME).sh3p
PROPS       := src/ApplicationPlugin.properties

UNAME       := $(shell uname -s)

# Prefer lib/SweetHome3D.jar if it's been dropped into the project; otherwise
# fall back to a platform-specific default. Override with `make SH3D_JAR=...`.
LOCAL_SH3D_JAR := lib/SweetHome3D.jar

ifeq ($(UNAME),Darwin)
DEFAULT_SH3D_JAR := /Applications/Sweet Home 3D.app/Contents/Java/SweetHome3D.jar
SH3D_PLUGIN_DIR  ?= $(HOME)/Library/Application Support/eTeks/Sweet Home 3D/plugins
SH3D_APP         ?= /Applications/Sweet Home 3D.app
else ifeq ($(UNAME),Linux)
DEFAULT_SH3D_JAR := /usr/share/sweethome3d/SweetHome3D.jar
SH3D_PLUGIN_DIR  ?= $(HOME)/.eteks/sweethome3d/plugins
else
DEFAULT_SH3D_JAR := $(LOCAL_SH3D_JAR)
SH3D_PLUGIN_DIR  ?= $(APPDATA)/eTeks/Sweet Home 3D/plugins
endif

ifneq ($(wildcard $(LOCAL_SH3D_JAR)),)
SH3D_JAR ?= $(LOCAL_SH3D_JAR)
else
SH3D_JAR ?= $(DEFAULT_SH3D_JAR)
endif

ANT ?= ant

# --- Targets -----------------------------------------------------------------

.PHONY: all package clean install reinstall run print-config \
        bump-major bump-minor bump-patch _bump

all: package

package $(SH3P):
	$(ANT) -Dsh3d.jar="$(SH3D_JAR)" package

clean:
	$(ANT) clean

install: $(SH3P)
	@if [ ! -d "$(SH3D_PLUGIN_DIR)" ]; then \
	  echo "Creating $(SH3D_PLUGIN_DIR)"; \
	  mkdir -p "$(SH3D_PLUGIN_DIR)"; \
	fi
	cp "$(SH3P)" "$(SH3D_PLUGIN_DIR)/"
	@echo "Installed to $(SH3D_PLUGIN_DIR)/$(PLUGIN_NAME).sh3p"
	@echo "Restart Sweet Home 3D to pick up the new build."

reinstall: clean install

run:
ifeq ($(UNAME),Darwin)
	open "$(SH3D_APP)"
else
	@echo "make run is only wired up for macOS; launch Sweet Home 3D manually."
endif

print-config:
	@echo "SH3D_JAR        = $(SH3D_JAR)"
	@echo "SH3D_PLUGIN_DIR = $(SH3D_PLUGIN_DIR)"
	@echo "SH3P            = $(SH3P)"
	@echo "VERSION         = $(shell grep '^version=' $(PROPS) | cut -d= -f2)"

# --- Version bumping --------------------------------------------------------
# Each target reads $(PROPS), increments the requested field, writes back,
# commits the change, and creates an annotated git tag. Push with
# `git push && git push --tags`.

bump-major:
	@$(MAKE) -s _bump TYPE=major

bump-minor:
	@$(MAKE) -s _bump TYPE=minor

bump-patch:
	@$(MAKE) -s _bump TYPE=patch

_bump:
	@if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then \
	    echo "Not inside a git repository — nothing to commit/tag." >&2; \
	    exit 1; \
	fi; \
	CUR=$$(grep '^version=' $(PROPS) | cut -d= -f2); \
	if [ -z "$$CUR" ]; then \
	    echo "version= not found in $(PROPS)" >&2; exit 1; \
	fi; \
	MAJ=$$(echo "$$CUR" | cut -d. -f1); \
	MIN=$$(echo "$$CUR" | cut -d. -f2); \
	PAT=$$(echo "$$CUR" | cut -d. -f3); \
	case "$(TYPE)" in \
	    major) NEW="$$((MAJ+1)).0.0" ;; \
	    minor) NEW="$$MAJ.$$((MIN+1)).0" ;; \
	    patch) NEW="$$MAJ.$$MIN.$$((PAT+1))" ;; \
	    *) echo "Unknown bump type: $(TYPE)" >&2; exit 1 ;; \
	esac; \
	if git rev-parse "v$$NEW" >/dev/null 2>&1; then \
	    echo "Tag v$$NEW already exists — aborting." >&2; exit 1; \
	fi; \
	sed -i.bak "s/^version=.*/version=$$NEW/" $(PROPS) && rm -f $(PROPS).bak; \
	git add $(PROPS); \
	git commit -m "chore: bump version to v$$NEW" -- $(PROPS) >/dev/null; \
	git tag -a "v$$NEW" -m "Release v$$NEW"; \
	echo; \
	echo "Bumped $$CUR -> $$NEW and created tag v$$NEW."; \
	echo "Push to publish (triggers CI release):"; \
	echo "    git push && git push --tags"
