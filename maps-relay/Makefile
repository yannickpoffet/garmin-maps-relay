# Connect IQ build. The SDK lives on the host under ~/.Garmin but runs inside
# the container (see Dockerfile for why), so every target shells through
# `docker compose run`.

DEVICE      ?= fr745
SDK         := /home/dev/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0/bin
KEY         := /home/dev/.Garmin/ConnectIQ/developer.der
OUT         := bin/maps-relay.prg
TYPECHECK   ?= 3
DC          := docker compose run --rm connectiq

# The MTP mount path contains a space ("Internal Storage"), which make's
# $(wildcard) splits on, so detection happens in the shell at recipe time.

.PHONY: build run sim package sideload watch-apps watch-log devices shell clean

## Compile for $(DEVICE) with strict type checking.
build:
	@mkdir -p bin
	$(DC) $(SDK)/monkeyc \
		-f monkey.jungle \
		-d $(DEVICE) \
		-o $(OUT) \
		-y $(KEY) \
		--typecheck $(TYPECHECK) \
		--warn

## Launch the simulator (leave running), then `make run` in another terminal.
sim:
	$(DC) $(SDK)/connectiq

## Push the built app into an already-running simulator.
run: build
	$(DC) $(SDK)/monkeydo $(OUT) $(DEVICE)

## Release build: signed, optimised, no debug symbols.
package:
	@mkdir -p bin
	$(DC) $(SDK)/monkeyc \
		-f monkey.jungle \
		-d $(DEVICE) \
		-o $(OUT) \
		-y $(KEY) \
		--typecheck $(TYPECHECK) \
		-r

## Copy the .prg onto the watch over MTP.
##
## Ubuntu's gvfs MTP backend refuses writes here ("Operation not supported"),
## via both the FUSE mount and the mtp:// URI, so this drives libmtp directly.
## gvfsd-mtp must release the USB device first -- note `pkill -x`, since
## `pkill -f` would also match the make/shell process running this rule.
sideload: package
	-@pkill -x gvfsd-mtp 2>/dev/null; sleep 2
	python3 tools/mtp_push.py $(OUT) Apps
	@echo "Unplug the watch, then find 'Maps Relay' in the app list."

## List the .prg files currently on the watch.
watch-apps:
	-@pkill -x gvfsd-mtp 2>/dev/null; sleep 2
	python3 tools/mtp_list.py

## Pull the watch's Connect IQ log (shows signature / API-level failures).
watch-log:
	-@pkill -x gvfsd-mtp 2>/dev/null; sleep 2
	python3 tools/mtp_pull_log.py

## List device targets currently installed.
devices:
	@ls ~/.Garmin/ConnectIQ/Devices 2>/dev/null || echo "none installed yet"

## Interactive shell in the SDK container.
shell:
	$(DC) bash

clean:
	rm -rf bin
