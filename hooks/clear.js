const fs = require('fs');

// https://gist.github.com/kethinov/6658166
var walkSync = function(dir, filelist) {
  var fs = fs || require('fs'),
      files = fs.readdirSync(dir);
  filelist = filelist || [];
  files.forEach(function(file) {
    if (fs.statSync(dir + file).isDirectory()) {
      filelist = walkSync(dir + file + '/', filelist);
    }
    else {
      filelist.push([dir, file]);
    }
  });
  return filelist;
};

const files = [];
const dir = process.argv[2];
walkSync(dir, files);

files.filter(
  (file) => file[1] === 'note.json'
).map(
  (file) => [file, JSON.parse(fs.readFileSync(file[0] + file[1]))]
).map(
  (file) => {
    console.log(file[0])
    file[1].paragraphs.map(
      (paragraph) => {
        paragraph.results = {};
        paragraph.config.results = {};
      }
    )

    return file;
  }    
).map(
  (file) => fs.writeFileSync(
    file[0][0] + file[0][1], 
    JSON.stringify(file[1], null, '  ') 
  )
)
