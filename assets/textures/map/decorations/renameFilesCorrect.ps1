ls | Rename-Item -NewName {$_ -replace 'forty-five_(asset_)?(.*?)(_?v\d+)?\.png$', '$2.png' }