XISO Droid, based on xvddfs

# xdvdfs

`xdvdfs` is a collection of tools for interacting with XDVDFS/XISO images.

If cargo is set up correctly in the path, it can be installed with:

```sh
cargo install xdvdfs-cli
```

Otherwise, it can be run from the workspace root as the default project.

A binary distribution of xdvdfs-cli is also available in the github releases.

### Usage

#### Packing an Image

Select your image via the user interface

This will create an iso that matches 1-to-1 with the input directory.

#### Repacking an Image

Images can be repacked from an existing ISO image

This will create an iso that matches 1-to-1 with the input image.

#### Additional images can be compressed or unpacked
