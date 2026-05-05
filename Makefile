# Convenience wrapper around build.xml. All real work happens in Ant.
#
# Usage:
#   make              # build dist/LasercutExport.sh3p
#   make clean        # remove build/ and dist/
#   make install      # copy .sh3p into the local Sweet Home 3D plugins folder
#   make reinstall    # clean + build + install
#   make run          # launch Sweet Home 3D (macOS only)
#
# Override the SDK location:
#   make SH3D_JAR=/path/to/SweetHome3D.jar

# --- Configuration -----------------------------------------------------------

PLUGIN_NAME := LasercutExport
SH3P        := dist/$(PLUGIN_NAME).sh3p

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

.PHONY: all package clean install reinstall run print-config

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
